/*
 *  Copyright 2017 Google Inc. All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.google.android.apps.forscience.whistlepunk.filemetadata;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;
import com.google.android.apps.forscience.whistlepunk.AppSingleton;
import com.google.android.apps.forscience.whistlepunk.Clock;
import com.google.android.apps.forscience.whistlepunk.ColorAllocator;
import com.google.android.apps.forscience.whistlepunk.PermissionUtils;
import com.google.android.apps.forscience.whistlepunk.R;
import com.google.android.apps.forscience.whistlepunk.WhistlePunkApplication;
import com.google.android.apps.forscience.whistlepunk.accounts.AppAccount;
import com.google.android.apps.forscience.whistlepunk.data.nano.GoosciDeviceSpec;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciExperiment;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciScalarSensorData;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.GoosciUserMetadata;
import com.google.android.apps.forscience.whistlepunk.metadata.nano.Version;
import com.google.android.apps.forscience.whistlepunk.sensorapi.ScalarSensorDumpReader;
import io.reactivex.Single;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/** MetadataManager backed by a file-based system using internal storage. */
// TODO: Extend MetadataManager
public class FileMetadataManager {
  public static final String COVER_IMAGE_FILE = "assets/ExperimentCoverImage.jpg";
  static final String ASSETS_DIRECTORY = "assets";
  public static final String EXPERIMENTS_DIRECTORY = "experiments";
  public static final String EXPERIMENT_FILE = "experiment.proto";
  public static final String EXPERIMENT_LIBRARY_FILE = "experiment_library.proto";
  public static final String SYNC_STATUS_FILE = "sync_status.proto";
  private static final String TAG = "FileMetadataManager";
  private static final String USER_METADATA_FILE = "user_metadata.proto";
  public static final String DOT_PROTO = ".proto";

  private AppAccount appAccount;
  private Clock clock;

  private ExperimentCache activeExperimentCache;
  private UserMetadataManager userMetadataManager;
  private final LocalSyncManager localSyncManager;
  private final ExperimentLibraryManager experimentLibraryManager;
  private ColorAllocator colorAllocator;

  public FileMetadataManager(Context applicationContext, AppAccount appAccount, Clock clock) {
    this(
        applicationContext,
        appAccount,
        clock,
        AppSingleton.getInstance(applicationContext).getExperimentLibraryManager(appAccount),
        AppSingleton.getInstance(applicationContext).getLocalSyncManager(appAccount));
  }

  public FileMetadataManager(
      Context applicationContext,
      AppAccount appAccount,
      Clock clock,
      ExperimentLibraryManager elm,
      LocalSyncManager lsm) {
    this.appAccount = appAccount;
    this.clock = clock;
    // TODO: Probably pass failure listeners from a higher level in order to propagate them
    // up to the user. b/62373187.
    ExperimentCache.FailureListener failureListener =
        new ExperimentCache.FailureListener() {
          @Override
          public void onWriteFailed(Experiment experimentToWrite) {
            // TODO: Propagate this up to the user somehow.
            Log.d(TAG, "write failed");
          }

          @Override
          public void onReadFailed(GoosciUserMetadata.ExperimentOverview experimentOverview) {
            // TODO: Propagate this up to the user somehow.
            Log.d(TAG, "read failed");
          }

          @Override
          public void onNewerVersionDetected(
              GoosciUserMetadata.ExperimentOverview experimentOverview) {
            // TODO: Propagate this up to the user somehow.
            Log.d(TAG, "newer proto version detected than we can handle");
          }
        };
    UserMetadataManager.FailureListener userMetadataListener =
        new UserMetadataManager.FailureListener() {

          @Override
          public void onWriteFailed() {
            // TODO: Propagate this up to the user somehow.
            Log.d(TAG, "write failed");
          }

          @Override
          public void onReadFailed() {
            // TODO: Propagate this up to the user somehow.
            Log.d(TAG, "read failed");
          }

          @Override
          public void onNewerVersionDetected() {
            // TODO: Propagate this up to the user somehow.
            Log.d(TAG, "newer proto version detected than we can handle");
          }
        };
    activeExperimentCache = new ExperimentCache(applicationContext, appAccount, failureListener);
    userMetadataManager =
        new UserMetadataManager(applicationContext, appAccount, userMetadataListener);
    localSyncManager = lsm;
    experimentLibraryManager = elm;
    colorAllocator =
        new ColorAllocator(
            applicationContext.getResources().getIntArray(R.array.experiment_colors_array).length);
  }

  /**
   * Deletes all experiments in the list of experiment IDs. This really deletes everything and
   * should be used very sparingly!
   */
  public void deleteAll(List<String> experimentIds) {
    for (String experimentId : experimentIds) {
      // This if block prevents null pointer exceptions in the upgrade flow.
      if (experimentLibraryManager.getExperiment(experimentId) == null) {
        experimentLibraryManager.addExperiment(experimentId);
        localSyncManager.addExperiment(experimentId);
      }
      deleteExperiment(experimentId);
    }
  }

  public Experiment getExperimentById(String experimentId) {
    GoosciUserMetadata.ExperimentOverview overview =
        userMetadataManager.getExperimentOverview(experimentId);
    if (overview == null) {
      return null;
    }
    return activeExperimentCache.getExperiment(overview);
  }

  public Experiment newExperiment() {
    long timestamp = clock.getNow();
    String localExperimentId = UUID.randomUUID().toString();
    List<GoosciUserMetadata.ExperimentOverview> overviews =
        userMetadataManager.getExperimentOverviews(true);
    int[] usedColors = new int[overviews.size()];
    for (int i = 0; i < overviews.size(); i++) {
      usedColors[i] = overviews.get(i).colorIndex;
    }
    int colorIndex = colorAllocator.getNextColor(usedColors);
    Experiment experiment = Experiment.newExperiment(timestamp, localExperimentId, colorIndex);
    localSyncManager.addExperiment(experiment.getExperimentId());
    experimentLibraryManager.addExperiment(experiment.getExperimentId());
    addExperiment(experiment);
    return experiment;
  }

  // Adds an existing experiment to the file system (rather than creating a new one).
  // This should just be used for data migration and testing.
  public void addExperiment(Experiment experiment) {
    // Get ready to write the experiment to a file. Will write when the timer expires.
    activeExperimentCache.createNewExperiment(experiment);
    userMetadataManager.addExperimentOverview(experiment.getExperimentOverview());
    localSyncManager.addExperiment(experiment.getExperimentId());
    experimentLibraryManager.addExperiment(experiment.getExperimentId());
  }

  public void saveImmediately() {
    activeExperimentCache.saveImmediately();
    userMetadataManager.saveImmediately();
  }

  public void deleteExperiment(Experiment experiment) {
    activeExperimentCache.prepareExperimentForDeletion(experiment);
    deleteExperiment(experiment.getExperimentId());
  }

  private void deleteExperiment(String experimentId) {
    activeExperimentCache.deleteExperiment(experimentId);
    userMetadataManager.deleteExperimentOverview(experimentId);
    experimentLibraryManager.setDeleted(experimentId, true);
  }

  public void beforeMovingAllExperimentsToAnotherAccount() {
    // This FileMetadataManager is losing all experiments.
    activeExperimentCache.beforeMovingAllExperimentsToAnotherAccount();
    userMetadataManager.deleteAllExperimentOverviews();
    experimentLibraryManager.setAllDeleted(true);
  }

  public void beforeMovingExperimentToAnotherAccount(Experiment experiment) {
    // This FileMetadataManager is losing the experiment.
    activeExperimentCache.beforeMovingExperimentToAnotherAccount(experiment.getExperimentId());
    userMetadataManager.deleteExperimentOverview(experiment.getExperimentId());
    experimentLibraryManager.setDeleted(experiment.getExperimentId(), true);
  }

  public void afterMovingExperimentFromAnotherAccount(Experiment experiment) {
    // This FileMetadataManager is gaining the experiment.
    userMetadataManager.addExperimentOverview(experiment.getExperimentOverview());
    localSyncManager.addExperiment(experiment.getExperimentId());
    experimentLibraryManager.addExperiment(experiment.getExperimentId());
    experimentLibraryManager.setModified(
        experiment.getExperimentId(), experiment.getLastUsedTime());
  }

  public void updateExperiment(Experiment experiment, boolean setDirty) {
    activeExperimentCache.updateExperiment(experiment, setDirty);

    // TODO: Only do this if strictly necessary, instead of every time?
    // Or does updateExperiment mean the last updated time should change, and we need a clock?
    userMetadataManager.updateExperimentOverview(experiment.getExperimentOverview());
  }

  public List<GoosciUserMetadata.ExperimentOverview> getExperimentOverviews(
      boolean includeArchived) {
    return userMetadataManager.getExperimentOverviews(includeArchived);
  }

  public Experiment getLastUsedUnarchivedExperiment() {
    List<GoosciUserMetadata.ExperimentOverview> overviews = getExperimentOverviews(false);
    long mostRecent = Long.MIN_VALUE;
    GoosciUserMetadata.ExperimentOverview overviewToGet = null;
    for (GoosciUserMetadata.ExperimentOverview overview : overviews) {
      if (overview.lastUsedTimeMs > mostRecent) {
        mostRecent = overview.lastUsedTimeMs;
        overviewToGet = overview;
      }
    }
    if (overviewToGet != null) {
      return activeExperimentCache.getExperiment(overviewToGet);
    }
    return null;
  }

  /**
   * Sets the most recently used experiment. This should only be used in testing -- the experiment
   * object should not actually be modified by FileMetadataManager.
   */
  @Deprecated
  public void setLastUsedExperiment(Experiment experiment) {
    long timestamp = clock.getNow();
    experiment.setLastUsedTime(timestamp);
    activeExperimentCache.onExperimentOverviewUpdated(experiment.getExperimentOverview());
    userMetadataManager.updateExperimentOverview(experiment.getExperimentOverview());
  }

  public void close() {
    saveImmediately();
  }

  /**
   * Imports an experiment from a ZIP file at the given URI, with the permissions of the Activity.
   */
  public Experiment importExperiment(Context context, Uri data, ContentResolver resolver)
      throws IOException {
    String experimentId = null;
    Context appContext = context.getApplicationContext();
    Experiment newExperiment = null;
    File externalPath = null;
    boolean containsExperimentImage;
    try {
      newExperiment = newExperiment();
      experimentId = newExperiment.getExperimentId();
      File externalFilesDir =
          FileMetadataUtil.getInstance().getExternalExperimentsDirectory(context);
      externalPath = new File(externalFilesDir, experimentId);
      File internalPath =
          FileMetadataUtil.getInstance().getExperimentDirectory(appAccount, experimentId);
      // Blocking get is ok as this is already on a background thread.
      containsExperimentImage =
          unzipExperimentFile(appContext, data, resolver, externalPath, internalPath).blockingGet();
    } catch (Exception e) {
      deleteExperiment(experimentId);
      throw e;
    }

    GoosciExperiment.Experiment proto = populateExperimentProto(context, externalPath);
    if (proto == null) {
      deleteExperiment(experimentId);
      throw new ZipException("Corrupt or Missing Experiment Proto");
    }
    if (!FileMetadataUtil.getInstance().canImportFromVersion(proto.fileVersion)) {
      deleteExperiment(experimentId);
      // TODO: better error message
      throw new ZipException("Cannot import from file version: " + versionToString(proto));
    }

    GoosciUserMetadata.ExperimentOverview overview = populateOverview(proto, experimentId);
    HashMap<String, String> trialIdMap = updateTrials(proto, newExperiment);

    updateLabels(proto, newExperiment);
    newExperiment.setTitle(proto.title);
    newExperiment.setLastUsedTime(clock.getNow());
    if (containsExperimentImage) {
      overview.imagePath = EXPERIMENTS_DIRECTORY + "/" + experimentId + "/" + COVER_IMAGE_FILE;
    }
    updateExperiment(Experiment.fromExperiment(proto, overview), true);
    File dataFile = new File(externalPath, "sensorData.proto");

    if (dataFile.exists()) {
      ProtoFileHelper<GoosciScalarSensorData.ScalarSensorData> dataProtoFileHelper =
          new ProtoFileHelper<>();
      GoosciScalarSensorData.ScalarSensorData dataProto =
          dataProtoFileHelper.readFromFile(
              dataFile,
              GoosciScalarSensorData.ScalarSensorData::parseFrom,
              WhistlePunkApplication.getUsageTracker(context));

      if (dataProto != null) {
        ScalarSensorDumpReader dumpReader =
            new ScalarSensorDumpReader(
                AppSingleton.getInstance(context)
                    .getSensorEnvironment()
                    .getDataController(appAccount));
        dumpReader.readData(dataProto, trialIdMap);
      }
    }

    return newExperiment;
  }

  private String versionToString(GoosciExperiment.Experiment proto) {
    Version.FileVersion fileVersion = proto.fileVersion;
    return fileVersion.version
        + "."
        + fileVersion.minorVersion
        + "."
        + fileVersion.platform.getNumber()
        + "."
        + fileVersion.platformVersion;
  }

  private Single<Boolean> unzipExperimentFile(
      Context context, Uri data, ContentResolver resolver, File externalPath, File internalPath)
      throws IOException {
    boolean containsExperimentImage = false;
    if (!externalPath.exists() && !externalPath.mkdir()) {
      throw new IOException("Couldn't create external experiment directory");
    }
    if (!internalPath.exists() && !internalPath.mkdir()) {
      throw new IOException("Couldn't create internal experiment directory");
    }
    File assetsDirectory = new File(internalPath, "assets");
    if (!assetsDirectory.exists() && !assetsDirectory.mkdir()) {
      throw new IOException("Couldn't create assets directory");
    }

    return Single.create(
        s -> {
          AppSingleton.getInstance(context)
              .onNextActivity()
              .subscribe(
                  activity -> {
                    PermissionUtils.tryRequestingPermission(
                        activity,
                        PermissionUtils.REQUEST_READ_EXTERNAL_STORAGE,
                        new PermissionUtils.PermissionListener() {
                          @Override
                          public void onPermissionGranted() {
                            Boolean containsImage = false;
                            try {
                              ZipInputStream zis =
                                  new ZipInputStream(resolver.openInputStream(data));
                              ZipEntry entry = zis.getNextEntry();
                              byte[] buffer = new byte[1024];

                              while (entry != null) {
                                String fileName = entry.getName();
                                if (fileName.equals("experiment.proto")
                                    || fileName.equals("sensorData.proto")) {
                                  FileOutputStream fos =
                                      new FileOutputStream(new File(externalPath, fileName));
                                  readZipInputStream(zis, buffer, fos);
                                } else if (fileName.matches(".*jpg")) {
                                  if (fileName.matches(COVER_IMAGE_FILE)) {
                                    containsImage = true;
                                  }
                                  FileOutputStream fos =
                                      new FileOutputStream(new File(internalPath, fileName));
                                  readZipInputStream(zis, buffer, fos);
                                }

                                entry = zis.getNextEntry();
                              }
                              zis.close();
                              s.onSuccess(containsImage);
                            } catch (Exception e) {
                              s.onError(e);
                            }
                          }

                          @Override
                          public void onPermissionDenied() {
                            s.onError(new IOException("Permission Denied"));
                          }

                          @Override
                          public void onPermissionPermanentlyDenied() {
                            s.onError(new IOException("Permission Denied"));
                          }
                        });
                  });
        });
  }

  private void readZipInputStream(ZipInputStream zis, byte[] buffer, FileOutputStream fos)
      throws IOException {
    int len;
    while ((len = zis.read(buffer)) > 0) {
      fos.write(buffer, 0, len);
    }
    fos.close();
  }

  private GoosciExperiment.Experiment populateExperimentProto(
      Context context, File experimentPath) {
    File experimentFile = new File(experimentPath, "experiment.proto");
    if (!experimentFile.exists()) {
      return null;
    }

    ProtoFileHelper<GoosciExperiment.Experiment> experimentProtoFileHelper =
        new ProtoFileHelper<>();
    GoosciExperiment.Experiment proto =
        experimentProtoFileHelper.readFromFile(
            experimentFile,
            GoosciExperiment.Experiment::parseFrom,
            WhistlePunkApplication.getUsageTracker(context));

    return proto;
  }

  private GoosciUserMetadata.ExperimentOverview populateOverview(
      GoosciExperiment.Experiment proto, String experimentId) {
    GoosciUserMetadata.ExperimentOverview overview = new GoosciUserMetadata.ExperimentOverview();
    overview.title = proto.title;
    overview.trialCount = proto.totalTrials;
    overview.lastUsedTimeMs = clock.getNow();
    overview.experimentId = experimentId;

    return overview;
  }

  private HashMap<String, String> updateTrials(
      GoosciExperiment.Experiment proto, Experiment newExperiment) {
    HashMap<String, String> trialIdMap = new HashMap<>();
    for (int i = 0; i < proto.trials.length; i++) {
      String oldId = proto.trials[i].trialId;
      Trial t = Trial.fromTrialWithNewId(proto.trials[i]);
      newExperiment.addTrial(t);
      proto.trials[i] = t.getTrialProto();
      trialIdMap.put(oldId, proto.trials[i].trialId);
    }
    return trialIdMap;
  }

  private void updateLabels(GoosciExperiment.Experiment proto, Experiment newExperiment) {
    for (int i = 0; i < proto.labels.length; i++) {
      Label label = Label.fromLabel(proto.labels[i]);
      newExperiment.addLabel(newExperiment, label);
    }
  }

  public void addMyDevice(GoosciDeviceSpec.DeviceSpec device) {
    userMetadataManager.addMyDevice(device);
  }

  public void removeMyDevice(GoosciDeviceSpec.DeviceSpec device) {
    userMetadataManager.removeMyDevice(device);
  }

  public List<GoosciDeviceSpec.DeviceSpec> getMyDevices() {
    return userMetadataManager.getMyDevices();
  }
}
