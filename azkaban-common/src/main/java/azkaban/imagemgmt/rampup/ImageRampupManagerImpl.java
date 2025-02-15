/*
 * Copyright 2020 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.imagemgmt.rampup;

import azkaban.Constants.ImageMgmtConstants;
import azkaban.executor.ExecutableFlow;
import azkaban.executor.container.ContainerImplUtils;
import azkaban.imagemgmt.daos.ImageRampupDao;
import azkaban.imagemgmt.daos.ImageTypeDao;
import azkaban.imagemgmt.daos.ImageVersionDao;
import azkaban.imagemgmt.daos.RampRuleDao;
import azkaban.imagemgmt.dto.ImageMetadataRequest;
import azkaban.imagemgmt.exception.ImageMgmtDaoException;
import azkaban.imagemgmt.exception.ImageMgmtException;
import azkaban.imagemgmt.models.ImageRampup;
import azkaban.imagemgmt.models.ImageType;
import azkaban.imagemgmt.models.ImageVersion;
import azkaban.imagemgmt.models.ImageVersion.State;
import azkaban.imagemgmt.models.ImageVersionMetadata;
import azkaban.imagemgmt.version.VersionInfo;
import azkaban.imagemgmt.version.VersionSet;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for fetching the version of the available images based on currently
 * active rampup plan and rampup details or the version which is already ramped up and active. Here
 * is the version selection process for an image type - 1. Fetch the rampup details for the given
 * image types or for all the image types (Two such methods are provided). 2. Sort the ramp up data
 * in ascending order of rampup percentage. 3. Generate a random number between 1 to 100 both
 * inclusive. Let us say the number the number generated is 60. 4. Let us say there are three
 * versions 1.1.1, 1.1.2 & and 1.1.3 with rampup percantages 10, 30 and 60 respectively. 5. The
 * above percentage fors three ranges [1 - 10], [11 - 40] & [41 - 100]. The random humber 60 belongs
 * to the last range i.e. [41 - 100] and hence version 1.1.3 will be selected. If random number
 * generated is 22 then version 1.1.2 will be selected and so on. 6. If there is no active rampup
 * plan for an image type or in the active plan if the version is marked unstable or deprecated, and
 * latest active version will be selected from the image_verions table. 7. If there is no active
 * version in the image_versions table, it will throw appropriate error message mentioning could not
 * select version for the image type and the whole process would fail. 8. Follow the rampup
 * procedure to elect a new version from the image_versions table for the failed image type.
 */
@Singleton
public class ImageRampupManagerImpl implements ImageRampupManager {

  private static final Logger log = LoggerFactory.getLogger(ImageRampupManagerImpl.class);
  private final ImageTypeDao imageTypeDao;
  private final ImageVersionDao imageVersionDao;
  private final ImageRampupDao imageRampupDao;
  private final RampRuleDao imageRampRuleDao;
  private static final String MSG_RANDOM_RAMPUP_VERSION_SELECTION = "The version selection is "
      + "based on deterministic rampup.";
  private static final String MSG_ACTIVE_VERSION_SELECTION = "The version selection is "
      + "based on latest available ACTIVE version.";
  private static final String MSG_NON_ACTIVE_VERSION_SELECTION = "Non ACTIVE "
      + "(i.e. NEW/UNSTABLE/DEPRECATED) latest version is selected as there is no active rampup "
      + "and ACTIVE version.";
  private static final String MSG_IMAGE_TYPE_WITHOUT_VERSION = "This image type does not have a "
      + "version yet.";

  @Inject
  public ImageRampupManagerImpl(final ImageRampupDao imageRampupDao,
      final ImageVersionDao imageVersionDao,
      final ImageTypeDao imageTypeDao,
      final RampRuleDao imageRampRule) {
    this.imageRampupDao = imageRampupDao;
    this.imageVersionDao = imageVersionDao;
    this.imageTypeDao = imageTypeDao;
    this.imageRampRuleDao = imageRampRule;
  }

  @Override
  public Map<String, VersionInfo> getVersionForAllImageTypes(final ExecutableFlow flow)
      throws ImageMgmtException {
    final Map<String, List<ImageRampup>> imageTypeRampups = this.imageRampupDao
        .getRampupForAllImageTypes();
    final List<ImageType> imageTypeList = this.imageTypeDao.getAllImageTypes();
    final Set<String> imageTypes = new TreeSet<>();
    for (final ImageType imageType : imageTypeList) {
      imageTypes.add(imageType.getName());
    }
    final Set<String> remainingImageTypes = new TreeSet<>();
    final Map<String, ImageVersionMetadata> imageTypeVersionMap = this
        .processAndGetVersionForImageTypes(flow, imageTypes, imageTypeRampups, remainingImageTypes);
    // Throw exception if there are left over image types
    if (!remainingImageTypes.isEmpty()) {
      throw new ImageMgmtException("Could not fetch version for below image types. Reasons: "
          + " 1. There is no active rampup plan in the image_rampup_plan table. 2. There is no "
          + " active version in the image_versions table. Image Types: " + remainingImageTypes);
    }
    return this.createVersionInfoMap(imageTypeVersionMap);
  }

  @Override
  public Map<String, ImageVersionMetadata> getVersionMetadataForAllImageTypes()
      throws ImageMgmtException {
    final Map<String, List<ImageRampup>> imageTypeRampups = this.imageRampupDao
        .getRampupForAllImageTypes();
    final List<ImageType> imageTypeList = this.imageTypeDao.getAllImageTypes();
    final Set<String> imageTypes = new TreeSet<>();
    for (final ImageType imageType : imageTypeList) {
      imageTypes.add(imageType.getName());
    }
    final Set<String> remainingImageTypes = new TreeSet<>();
    final Map<String, ImageVersionMetadata> imageTypeVersionMap =
        this.processAndGetVersionForImageTypes(null, imageTypes, imageTypeRampups,
            remainingImageTypes);
    if (!remainingImageTypes.isEmpty()) {
      final Map<String, ImageVersion> imageTypeLatestNonActiveVersionMap =
          this.getLatestNonActiveImageVersion(remainingImageTypes);
      log.info("imageTypeLatestNonActiveVersionMap: " + imageTypeLatestNonActiveVersionMap);
      imageTypeLatestNonActiveVersionMap.forEach((k, v) -> imageTypeVersionMap.put(k,
          new ImageVersionMetadata(v, MSG_NON_ACTIVE_VERSION_SELECTION)));
      if (!remainingImageTypes.isEmpty()) {
        remainingImageTypes.forEach(k -> imageTypeVersionMap.put(k,
            new ImageVersionMetadata(null, MSG_IMAGE_TYPE_WITHOUT_VERSION)));
      }
    }
    return imageTypeVersionMap;
  }

  @Override
  public Map<String, VersionInfo> validateAndGetUpdatedVersionMap(
      final ExecutableFlow executableFlow, final VersionSet versionSet)
      throws ImageMgmtException {
    // Find the image types for which version is either invalid or not exists
    final Set<String> imageTypesWithInvalidVersion = versionSet.getImageToVersionMap().entrySet()
        .stream()
        .filter(map -> this.imageVersionDao.isInvalidVersion(
            map.getKey(), map.getValue().getVersion()))
        .map(map -> map.getKey())
        .collect(Collectors.toSet());
    final Map<String, VersionInfo> updatedVersionInfoMap = new TreeMap<>(
        String.CASE_INSENSITIVE_ORDER);
    if (!imageTypesWithInvalidVersion.isEmpty()) {
      final Map<String, VersionInfo> versionInfoMap = this
          .getVersionByImageTypes(executableFlow, imageTypesWithInvalidVersion, new HashSet<>());
      // Update the correct version in versionSet
      versionInfoMap.forEach((k, v) -> updatedVersionInfoMap.put(k, v));
      versionSet.getImageToVersionMap().entrySet()
          .forEach(map -> updatedVersionInfoMap.putIfAbsent(map.getKey(), map.getValue()));
    }
    return updatedVersionInfoMap;
  }

  @Override
  public Map<String, VersionInfo> getVersionByImageTypes(final ExecutableFlow flow,
      final Set<String> imageTypes, Set<String> overlayImageTypes)
      throws ImageMgmtException {
    final Map<String, List<ImageRampup>> imageTypeRampups = this.imageRampupDao
        .getRampupByImageTypes(imageTypes);
    final Set<String> remainingImageTypes = new TreeSet<>();
    final Map<String, ImageVersionMetadata> imageTypeVersionMap =
        this.processAndGetVersionForImageTypes(flow, imageTypes, imageTypeRampups,
            remainingImageTypes);
    // Exclude images defined by flow parameter image.{image-type-name}.version
    remainingImageTypes.removeAll(overlayImageTypes);
    // Throw exception if there are left over image types
    if (!remainingImageTypes.isEmpty()) {
      throw new ImageMgmtException("Could not fetch version for below image types. Reasons: "
          + " 1. There is no active rampup plan in the image_rampup_plan table. 2. There is no "
          + " active version in the image_versions table. Image Types: " + remainingImageTypes);
    }
    return this.createVersionInfoMap(imageTypeVersionMap);
  }

  @Override
  public VersionInfo getVersionInfo(final String imageType, final String imageVersion,
      final Set<State> stateFilter) throws ImageMgmtException {
    final Optional<ImageVersion> optionalImageVersion = this
        .fetchImageVersion(imageType, imageVersion);
    // If state filter is null or empty return the version info directly.
    // If state filter is present, apply the filter and return version info
    if (optionalImageVersion.isPresent() &&
        ((stateFilter.isEmpty() || stateFilter == null) ||
            stateFilter.contains(optionalImageVersion.get().getState()))) {
      return new VersionInfo(optionalImageVersion.get().getVersion(),
          optionalImageVersion.get().getPath(), optionalImageVersion.get().getState());
    } else {
      throw new ImageMgmtException(String.format("Unable to get VersionInfo for image type: %s, "
          + "image version: %s with NEW or ACTIVE state.", imageType, imageVersion));
    }
  }

  /**
   * This method processes image type rampup details for the image type and selects a version for
   * the image type. Here is the version selection process for an image type 1. Sort the ramp up
   * data in the ascending order of rampup percentage. 2. Generate a random number between 1 to 100
   * both inclusive. Let us say the number the number generated is 60. 3. Let us say there are three
   * versions 1.1.1, 1.1.2 & and 1.1.3 with rampup percantages 10, 30 & 60 respectively. 4. The
   * above percentage fors three ranges [1 - 10], [11 - 40] & [41 - 100]. The random humber 60
   * belongs to the last range i.e. [41 - 100] and hence version 1.1.3 will be selected. If random
   * number generated is 22 then version 1.1.2 will be selected and so on. 5. If there is no active
   * rampup plan for an image type or in the active plan if the version is marked unstable or
   * deprecated, and latest active version will be selected from the image_verions table. 6. If
   * there is no active version in the image_versions table, it will throw appropriate error message
   * mentioning could not select version for the image type and the whole process would fail. 7.
   * Follow the rampup procedure to elect a new version from the image_versions table for the ailed
   * image type.
   *
   * @param imageTypes          - set of specified image types
   * @param imageTypeRampups    - contains rampup list for an image type
   * @param remainingImageTypes - This set is used to keep track of the image types for which
   *                            version metadata is not available.
   * @return Map<String, VersionMetadata>
   */
  private Map<String, ImageVersionMetadata> processAndGetVersionForImageTypes(
      final ExecutableFlow flow,
      final Set<String> imageTypes,
      final Map<String, List<ImageRampup>> imageTypeRampups,
      final Set<String> remainingImageTypes) {
    final Set<String> rampupImageTypeSet = imageTypeRampups.keySet();
    log.info("Found active rampup for the image types {} ", rampupImageTypeSet);
    final Map<String, ImageVersionMetadata> imageTypeVersionMap = new TreeMap<>(
        String.CASE_INSENSITIVE_ORDER);
    // select current flow's image versions based on ramp up plan and ramp rule(exclusive list)
    final Map<String, ImageVersion> imageTypeRampupVersionMap =
        this.processAndGetRampupVersion(flow, imageTypeRampups);
    imageTypeRampupVersionMap
        .forEach((k, v) -> imageTypeVersionMap.put(k, new ImageVersionMetadata(v,
            imageTypeRampups.get(k), MSG_RANDOM_RAMPUP_VERSION_SELECTION)));
    log.info("After processing rampup records -> imageTypeVersionMap: {}", imageTypeVersionMap);

    /*
     * Fetching the latest active image version from image_versions table for the remaining image
     * types for which there is no active rampup plan or the versions are marked as
     * unstable/deprecated in the active plan.
     */
    // Converts the input image types to lowercase for case insensitive comparison.
    final Set<String> imageTypesInLowerCase =
        imageTypes.stream().map(String::toLowerCase).collect(Collectors.toSet());
    remainingImageTypes.addAll(imageTypesInLowerCase);
    remainingImageTypes
        .removeAll(imageTypeVersionMap.keySet().stream().map(String::toLowerCase).collect(
            Collectors.toSet()));
    log.info("After finding version through rampup image types remaining: {}  ",
        remainingImageTypes);
    final Map<String, ImageVersion> imageTypeActiveVersionMap =
        this.processAndGetActiveImageVersion(remainingImageTypes);
    imageTypeActiveVersionMap
        .forEach((k, v) -> imageTypeVersionMap.put(k,
            new ImageVersionMetadata(v, MSG_ACTIVE_VERSION_SELECTION)));
    log.info("After fetching active image version -> imageTypeVersionMap {}", imageTypeVersionMap);

    // For the leftover image types throw exception with appropriate error message.
    remainingImageTypes
        .removeAll(imageTypeVersionMap.keySet().stream().map(String::toLowerCase).collect(
            Collectors.toSet()));
    log.info("After fetching version using ramp up and based on active image version the "
        + "image types remaining: {}  ", remainingImageTypes);
    return imageTypeVersionMap;
  }

  /**
   * Method to process the rampup list and get the rampup version based on rampup logic for
   * the given image types in the rampup map.
   * If there is a ramp rule defined for this ramp up plan, the image version would be deselected
   * and use the current active version instead.
   *
   * @param imageTypeRampups
   * @return Map<String, ImageVersion>
   */
  private Map<String, ImageVersion> processAndGetRampupVersion(
      final ExecutableFlow flow,
      final Map<String, List<ImageRampup>> imageTypeRampups) {
    final Set<String> rampupImageTypeSet = imageTypeRampups.keySet();
    log.info("Found active rampup for the image types {} ", rampupImageTypeSet);
    final Map<String, ImageVersion> imageTypeRampupVersionMap =
        new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (rampupImageTypeSet.isEmpty()) {
      log.warn("No active rampup found for the image types");
      return imageTypeRampupVersionMap;
    }
    log.info("Found active rampup for the image types {} ", rampupImageTypeSet);
    for (final String imageTypeName : rampupImageTypeSet) {
      final List<ImageRampup> imageRampupList = imageTypeRampups.get(imageTypeName);
      if (imageRampupList.isEmpty()) {
        log.info("ImageRampupList was empty, so continue");
        continue;
      }
      if (null == flow) {
        log.info("Flow object is null, so continue");
        final ImageRampup firstImageRampup = imageRampupList.get(0);
        imageTypeRampupVersionMap.put(imageTypeName,
            this.fetchImageVersion(imageTypeName, firstImageRampup.getImageVersion())
                .orElseThrow(() -> new ImageMgmtException(
                    String.format("Unable to fetch version %s from image " + "versions table.",
                        firstImageRampup.getImageVersion()))));
      } else {
        int prevRampupPercentage = 0;
        final int flowNameHashValMapping = ContainerImplUtils.getFlowNameHashValMapping(flow);
        log.info("HashValMapping: " + flowNameHashValMapping);
        for (final ImageRampup imageRampup : imageRampupList) {
          final int rampupPercentage = imageRampup.getRampupPercentage();
          if (flowNameHashValMapping >= prevRampupPercentage + 1
              && flowNameHashValMapping <= prevRampupPercentage + rampupPercentage) {
            // when flow is excluded by a ramp rule, will use default active version for that image type
            if (imageRampRuleDao.isExcludedByRampRule(
                flow.getFlowName(), imageTypeName, imageRampup.getImageVersion())) {
              imageTypeRampupVersionMap.put(imageTypeName, fetchActiveImageVersion(imageTypeName)
                  .orElseThrow(() -> new ImageMgmtDaoException(
                      "fail to find active image version for {}" + imageTypeName)));
              log.debug("The image version {} is deselected for image type {} with rampup percentage {} "
                      + "and use active current one",
                  imageRampup.getImageVersion(), imageTypeName, rampupPercentage);
            } else {
              imageTypeRampupVersionMap.put(imageTypeName,
                  this.fetchImageVersion(imageTypeName, imageRampup.getImageVersion())
                      .orElseThrow(() -> new ImageMgmtException(
                          String.format("Unable to fetch version %s from image " + "versions table.", imageRampup.getImageVersion()))));
              log.debug("The image version {} is selected for image type {} with rampup percentage {}", imageRampup.getImageVersion(), imageTypeName, rampupPercentage);
            }
            break;
          }
          log.info("ImageTypeRampupVersionMap: " + imageTypeRampupVersionMap);
          prevRampupPercentage += rampupPercentage;
        }
      }
    }
    return imageTypeRampupVersionMap;
  }

  /**
   * Process and get latest active image version for the given image types.
   *
   * @param imageTypes
   * @return Map<String, ImageVersion>
   */
  private Map<String, ImageVersion> processAndGetActiveImageVersion(final Set<String> imageTypes) {
    final Map<String, ImageVersion> imageTypeActiveVersionMap =
        new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (!CollectionUtils.isEmpty(imageTypes)) {
      final List<ImageVersion> imageVersions =
          this.imageVersionDao.getActiveVersionByImageTypes(imageTypes);
      log.debug("Active image versions fetched: {} ", imageVersions);
      if (imageVersions != null && !imageVersions.isEmpty()) {
        for (final ImageVersion imageVersion : imageVersions) {
          imageTypeActiveVersionMap.put(imageVersion.getName(), imageVersion);
        }
      }
    }
    return imageTypeActiveVersionMap;
  }

  /**
   * Get latest non active image version for the given image types.
   *
   * @param imageTypes
   * @return Map<String, ImageVersion>
   */
  private Map<String, ImageVersion> getLatestNonActiveImageVersion(final Set<String> imageTypes) {
    final Map<String, ImageVersion> imageTypeLatestNonActiveVersionMap =
        new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (!CollectionUtils.isEmpty(imageTypes)) {
      final List<ImageVersion> imageVersions =
          this.imageVersionDao.getLatestNonActiveVersionByImageTypes(imageTypes);
      log.info("Non Active image versions fetched: {} ", imageVersions);
      if (imageVersions != null && !imageVersions.isEmpty()) {
        for (final ImageVersion imageVersion : imageVersions) {
          imageTypeLatestNonActiveVersionMap.put(imageVersion.getName(), imageVersion);
        }
        // Retain the the remaining/left over image types (i.e. image types without any version)
        imageTypes.removeAll(imageTypeLatestNonActiveVersionMap.keySet().stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet()));
      }
    }
    return imageTypeLatestNonActiveVersionMap;
  }

  /**
   * Method to fetch image version based on image type and image version.
   *
   * @param imageType
   * @param imageVersion
   * @return Optional<ImageVersion>
   */
  private Optional<ImageVersion> fetchImageVersion(final String imageType,
      final String imageVersion) {
    final ImageMetadataRequest imageMetadataRequest = ImageMetadataRequest.newBuilder()
        .addParam(ImageMgmtConstants.IMAGE_TYPE, imageType)
        .addParam(ImageMgmtConstants.IMAGE_VERSION, imageVersion)
        .build();
    final List<ImageVersion> imageVersions = this.imageVersionDao
        .findImageVersions(imageMetadataRequest);
    if (CollectionUtils.isEmpty(imageVersions)) {
      return Optional.empty();
    }
    // Return only the imageVersion only when the image type/name matches
    for (final ImageVersion version : imageVersions) {
      if (version.getName().equalsIgnoreCase(imageType) && version.getVersion()
          .equalsIgnoreCase(imageVersion)) {
        return Optional.of(version);
      }
    }
    return Optional.empty();
  }

  /**
   * Method to fetch active image version based on given image type.
   *
   * @param imageType
   * @return Optional<ImageVersion>
   */
  private Optional<ImageVersion> fetchActiveImageVersion(String imageType) {
      Set<String> imageTypeSet = new HashSet<>();
      imageTypeSet.add(imageType);
      List<ImageVersion> imageVersions = imageVersionDao.getActiveVersionByImageTypes(imageTypeSet);
      if (imageVersions.isEmpty()) {
        log.debug("found no active image version for {}", imageType);
        return Optional.empty();
      } else {
        log.debug("fetch active image version {} for {}", imageVersions.get(0).getVersion(), imageType);
        return Optional.of(imageVersions.get(0));
      }
  }

  /**
   * Creates VersionInfo map from the ImageVersionMetadata map for the given image type keys.
   *
   * @param imageVersionMetadataMap
   * @return Map<String, VersionInfo>
   */
  private Map<String, VersionInfo> createVersionInfoMap(
      final Map<String, ImageVersionMetadata> imageVersionMetadataMap) {
    final Map<String, VersionInfo> versionInfoMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    imageVersionMetadataMap.forEach((k, v) -> versionInfoMap.put(k,
        new VersionInfo(v.getImageVersion().getVersion(), v.getImageVersion().getPath(),
            v.getImageVersion().getState())));
    return versionInfoMap;
  }

  /**
   * Return rampup percentage comparator
   *
   * @return Comparator<ImageRampup>
   */
  private Comparator<ImageRampup> getRampupPercentageComparator() {
    return Comparator.comparingInt(ImageRampup::getRampupPercentage);
  }
}
