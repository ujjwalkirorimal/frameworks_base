/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.server.pm.dex;

import android.util.AtomicFile;
import android.util.Slog;
import android.os.Build;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastPrintWriter;
import com.android.server.pm.AbstractStatsBase;
import com.android.server.pm.PackageManagerServiceUtils;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dalvik.system.VMRuntime;
import libcore.io.IoUtils;
import libcore.util.Objects;

/**
 * Stat file which store usage information about dex files.
 */
public class PackageDexUsage extends AbstractStatsBase<Void> {
    private final static String TAG = "PackageDexUsage";

    // The last version update: add class loader contexts for secondary dex files.
    private final static int PACKAGE_DEX_USAGE_VERSION = 3;
    // We support previous version to ensure that the usage list remains valid cross OTAs.
    private final static int PACKAGE_DEX_USAGE_SUPPORTED_VERSION_1 = 1;
    // Version 2 added the list of packages that load the dex files.
    private final static int PACKAGE_DEX_USAGE_SUPPORTED_VERSION_2 = 2;

    private final static String PACKAGE_DEX_USAGE_VERSION_HEADER =
            "PACKAGE_MANAGER__PACKAGE_DEX_USAGE__";

    private final static String SPLIT_CHAR = ",";
    private final static String DEX_LINE_CHAR = "#";
    private final static String LOADING_PACKAGE_CHAR = "@";

    // One of the things we record about dex files is the class loader context that was used to
    // load them. That should be stable but if it changes we don't keep track of variable contexts.
    // Instead we put a special marker in the dex usage file in order to recognize the case and
    // skip optimizations on that dex files.
    /*package*/ static final String VARIABLE_CLASS_LOADER_CONTEXT =
            "=VariableClassLoaderContext=";
    // The marker used for unsupported class loader contexts.
    /*package*/ static final String UNSUPPORTED_CLASS_LOADER_CONTEXT =
            "=UnsupportedClassLoaderContext=";
    // The markers used for unknown class loader contexts. This can happen if the dex file was
    // recorded in a previous version and we didn't have a chance to update its usage.
    /*package*/ static final String UNKNOWN_CLASS_LOADER_CONTEXT =
            "=UnknownClassLoaderContext=";

    // Map which structures the information we have on a package.
    // Maps package name to package data (which stores info about UsedByOtherApps and
    // secondary dex files.).
    // Access to this map needs synchronized.
    @GuardedBy("mPackageUseInfoMap")
    private final Map<String, PackageUseInfo> mPackageUseInfoMap;

    public PackageDexUsage() {
        super("package-dex-usage.list", "PackageDexUsage_DiskWriter", /*lock*/ false);
        mPackageUseInfoMap = new HashMap<>();
    }

    /**
     * Record a dex file load.
     *
     * Note this is called when apps load dex files and as such it should return
     * as fast as possible.
     *
     * @param owningPackageName the package owning the dex path
     * @param dexPath the path of the dex files being loaded
     * @param ownerUserId the user id which runs the code loading the dex files
     * @param loaderIsa the ISA of the app loading the dex files
     * @param isUsedByOtherApps whether or not this dex file was not loaded by its owning package
     * @param primaryOrSplit whether or not the dex file is a primary/split dex. True indicates
     *        the file is either primary or a split. False indicates the file is secondary dex.
     * @param loadingPackageName the package performing the load. Recorded only if it is different
     *        than {@param owningPackageName}.
     * @return true if the dex load constitutes new information, or false if this information
     *         has been seen before.
     */
    public boolean record(String owningPackageName, String dexPath, int ownerUserId,
            String loaderIsa, boolean isUsedByOtherApps, boolean primaryOrSplit,
            String loadingPackageName, String classLoaderContext) {
        if (!PackageManagerServiceUtils.checkISA(loaderIsa)) {
            throw new IllegalArgumentException("loaderIsa " + loaderIsa + " is unsupported");
        }
        if (classLoaderContext == null) {
            throw new IllegalArgumentException("Null classLoaderContext");
        }

        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(owningPackageName);
            if (packageUseInfo == null) {
                // This is the first time we see the package.
                packageUseInfo = new PackageUseInfo();
                if (primaryOrSplit) {
                    // If we have a primary or a split apk, set isUsedByOtherApps.
                    // We do not need to record the loaderIsa or the owner because we compile
                    // primaries for all users and all ISAs.
                    packageUseInfo.mIsUsedByOtherApps = isUsedByOtherApps;
                    maybeAddLoadingPackage(owningPackageName, loadingPackageName,
                            packageUseInfo.mLoadingPackages);
                } else {
                    // For secondary dex files record the loaderISA and the owner. We'll need
                    // to know under which user to compile and for what ISA.
                    DexUseInfo newData = new DexUseInfo(isUsedByOtherApps, ownerUserId,
                            classLoaderContext, loaderIsa);
                    packageUseInfo.mDexUseInfoMap.put(dexPath, newData);
                    maybeAddLoadingPackage(owningPackageName, loadingPackageName,
                            newData.mLoadingPackages);
                }
                mPackageUseInfoMap.put(owningPackageName, packageUseInfo);
                return true;
            } else {
                // We already have data on this package. Amend it.
                if (primaryOrSplit) {
                    // We have a possible update on the primary apk usage. Merge
                    // isUsedByOtherApps information and return if there was an update.
                    boolean updateLoadingPackages = maybeAddLoadingPackage(owningPackageName,
                            loadingPackageName, packageUseInfo.mLoadingPackages);
                    return packageUseInfo.merge(isUsedByOtherApps) || updateLoadingPackages;
                } else {
                    DexUseInfo newData = new DexUseInfo(
                            isUsedByOtherApps, ownerUserId, classLoaderContext, loaderIsa);
                    boolean updateLoadingPackages = maybeAddLoadingPackage(owningPackageName,
                            loadingPackageName, newData.mLoadingPackages);

                    DexUseInfo existingData = packageUseInfo.mDexUseInfoMap.get(dexPath);
                    if (existingData == null) {
                        // It's the first time we see this dex file.
                        packageUseInfo.mDexUseInfoMap.put(dexPath, newData);
                        return true;
                    } else {
                        if (ownerUserId != existingData.mOwnerUserId) {
                            // Oups, this should never happen, the DexManager who calls this should
                            // do the proper checks and not call record if the user does not own the
                            // dex path.
                            // Secondary dex files are stored in the app user directory. A change in
                            // owningUser for the same path means that something went wrong at some
                            // higher level, and the loaderUser was allowed to cross
                            // user-boundaries and access data from what we know to be the owner
                            // user.
                            throw new IllegalArgumentException("Trying to change ownerUserId for "
                                    + " dex path " + dexPath + " from " + existingData.mOwnerUserId
                                    + " to " + ownerUserId);
                        }
                        // Merge the information into the existing data.
                        // Returns true if there was an update.
                        return existingData.merge(newData) || updateLoadingPackages;
                    }
                }
            }
        }
    }

    /**
     * Convenience method for sync reads which does not force the user to pass a useless
     * (Void) null.
     */
    public void read() {
      read((Void) null);
    }

    /**
     * Convenience method for async writes which does not force the user to pass a useless
     * (Void) null.
     */
    public void maybeWriteAsync() {
      maybeWriteAsync((Void) null);
    }

    @Override
    protected void writeInternal(Void data) {
        AtomicFile file = getFile();
        FileOutputStream f = null;

        try {
            f = file.startWrite();
            OutputStreamWriter osw = new OutputStreamWriter(f);
            write(osw);
            osw.flush();
            file.finishWrite(f);
        } catch (IOException e) {
            if (f != null) {
                file.failWrite(f);
            }
            Slog.e(TAG, "Failed to write usage for dex files", e);
        }
    }

    /**
     * File format:
     *
     * file_magic_version
     * package_name_1
     * @ loading_package_1_1, loading_package_1_2...
     * #dex_file_path_1_1
     * @ loading_package_1_1_1, loading_package_1_1_2...
     * user_1_1, used_by_other_app_1_1, user_isa_1_1_1, user_isa_1_1_2
     * #dex_file_path_1_2
     * @ loading_package_1_2_1, loading_package_1_2_2...
     * user_1_2, used_by_other_app_1_2, user_isa_1_2_1, user_isa_1_2_2
     * ...
     * package_name_2
     * @ loading_package_2_1, loading_package_2_1_2...
     * #dex_file_path_2_1
     * @ loading_package_2_1_1, loading_package_2_1_2...
     * user_2_1, used_by_other_app_2_1, user_isa_2_1_1, user_isa_2_1_2
     * #dex_file_path_2_2,
     * @ loading_package_2_2_1, loading_package_2_2_2...
     * user_2_2, used_by_other_app_2_2, user_isa_2_2_1, user_isa_2_2_2
     * ...
    */
    /* package */ void write(Writer out) {
        // Make a clone to avoid locking while writing to disk.
        Map<String, PackageUseInfo> packageUseInfoMapClone = clonePackageUseInfoMap();

        FastPrintWriter fpw = new FastPrintWriter(out);

        // Write the header.
        fpw.print(PACKAGE_DEX_USAGE_VERSION_HEADER);
        fpw.println(PACKAGE_DEX_USAGE_VERSION);

        for (Map.Entry<String, PackageUseInfo> pEntry : packageUseInfoMapClone.entrySet()) {
            // Write the package line.
            String packageName = pEntry.getKey();
            PackageUseInfo packageUseInfo = pEntry.getValue();

            fpw.println(String.join(SPLIT_CHAR, packageName,
                    writeBoolean(packageUseInfo.mIsUsedByOtherApps)));
            fpw.println(LOADING_PACKAGE_CHAR +
                    String.join(SPLIT_CHAR, packageUseInfo.mLoadingPackages));

            // Write dex file lines.
            for (Map.Entry<String, DexUseInfo> dEntry : packageUseInfo.mDexUseInfoMap.entrySet()) {
                String dexPath = dEntry.getKey();
                DexUseInfo dexUseInfo = dEntry.getValue();
                fpw.println(DEX_LINE_CHAR + dexPath);
                fpw.println(LOADING_PACKAGE_CHAR +
                        String.join(SPLIT_CHAR, dexUseInfo.mLoadingPackages));
                fpw.println(dexUseInfo.getClassLoaderContext());

                fpw.print(String.join(SPLIT_CHAR, Integer.toString(dexUseInfo.mOwnerUserId),
                        writeBoolean(dexUseInfo.mIsUsedByOtherApps)));
                for (String isa : dexUseInfo.mLoaderIsas) {
                    fpw.print(SPLIT_CHAR + isa);
                }
                fpw.println();
            }
        }
        fpw.flush();
    }

    @Override
    protected void readInternal(Void data) {
        AtomicFile file = getFile();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(file.openRead()));
            read(in);
        } catch (FileNotFoundException expected) {
            // The file may not be there. E.g. When we first take the OTA with this feature.
        } catch (IOException e) {
            Slog.w(TAG, "Failed to parse package dex usage.", e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    /* package */ void read(Reader reader) throws IOException {
        Map<String, PackageUseInfo> data = new HashMap<>();
        BufferedReader in = new BufferedReader(reader);
        // Read header, do version check.
        String versionLine = in.readLine();
        int version;
        if (versionLine == null) {
            throw new IllegalStateException("No version line found.");
        } else {
            if (!versionLine.startsWith(PACKAGE_DEX_USAGE_VERSION_HEADER)) {
                // TODO(calin): the caller is responsible to clear the file.
                throw new IllegalStateException("Invalid version line: " + versionLine);
            }
            version = Integer.parseInt(
                    versionLine.substring(PACKAGE_DEX_USAGE_VERSION_HEADER.length()));
            if (!isSupportedVersion(version)) {
                throw new IllegalStateException("Unexpected version: " + version);
            }
        }

        String s;
        String currentPackage = null;
        PackageUseInfo currentPackageData = null;

        Set<String> supportedIsas = new HashSet<>();
        for (String abi : Build.SUPPORTED_ABIS) {
            supportedIsas.add(VMRuntime.getInstructionSet(abi));
        }
        while ((s = in.readLine()) != null) {
            if (s.startsWith(DEX_LINE_CHAR)) {
                // This is the start of the the dex lines.
                // We expect 4 lines for each dex entry:
                // #dexPaths
                // @loading_package_1,loading_package_2,...
                // class_loader_context
                // onwerUserId,isUsedByOtherApps,isa1,isa2
                if (currentPackage == null) {
                    throw new IllegalStateException(
                        "Malformed PackageDexUsage file. Expected package line before dex line.");
                }

                // First line is the dex path.
                String dexPath = s.substring(DEX_LINE_CHAR.length());

                // In version 2 the second line contains the list of packages that loaded the file.
                List<String> loadingPackages = maybeReadLoadingPackages(in, version);
                // In version 3 the third line contains the class loader context.
                String classLoaderContext = maybeReadClassLoaderContext(in, version);

                // Next line is the dex data.
                s = in.readLine();
                if (s == null) {
                    throw new IllegalStateException("Could not find dexUseInfo line");
                }

                // We expect at least 3 elements (isUsedByOtherApps, userId, isa).
                String[] elems = s.split(SPLIT_CHAR);
                if (elems.length < 3) {
                    throw new IllegalStateException("Invalid PackageDexUsage line: " + s);
                }
                int ownerUserId = Integer.parseInt(elems[0]);
                boolean isUsedByOtherApps = readBoolean(elems[1]);
                DexUseInfo dexUseInfo = new DexUseInfo(isUsedByOtherApps, ownerUserId,
                        classLoaderContext, /*isa*/ null);
                dexUseInfo.mLoadingPackages.addAll(loadingPackages);
                for (int i = 2; i < elems.length; i++) {
                    String isa = elems[i];
                    if (supportedIsas.contains(isa)) {
                        dexUseInfo.mLoaderIsas.add(elems[i]);
                    } else {
                        // Should never happen unless someone crafts the file manually.
                        // In theory it could if we drop a supported ISA after an OTA but we don't
                        // do that.
                        Slog.wtf(TAG, "Unsupported ISA when parsing PackageDexUsage: " + isa);
                    }
                }
                if (supportedIsas.isEmpty()) {
                    Slog.wtf(TAG, "Ignore dexPath when parsing PackageDexUsage because of " +
                            "unsupported isas. dexPath=" + dexPath);
                    continue;
                }
                currentPackageData.mDexUseInfoMap.put(dexPath, dexUseInfo);
            } else {
                // This is a package line.
                // We expect it to be: `packageName,isUsedByOtherApps`.
                String[] elems = s.split(SPLIT_CHAR);
                if (elems.length != 2) {
                    throw new IllegalStateException("Invalid PackageDexUsage line: " + s);
                }
                currentPackage = elems[0];
                currentPackageData = new PackageUseInfo();
                currentPackageData.mIsUsedByOtherApps = readBoolean(elems[1]);
                currentPackageData.mLoadingPackages.addAll(maybeReadLoadingPackages(in, version));
                data.put(currentPackage, currentPackageData);
            }
        }

        synchronized (mPackageUseInfoMap) {
            mPackageUseInfoMap.clear();
            mPackageUseInfoMap.putAll(data);
        }
    }

    /**
     * Reads the class loader context encoding from the buffer {@code in} if
     * {@code version} is at least {PACKAGE_DEX_USAGE_VERSION}.
     */
    private String maybeReadClassLoaderContext(BufferedReader in, int version) throws IOException {
        String context = null;
        if (version == PACKAGE_DEX_USAGE_VERSION) {
            context = in.readLine();
            if (context == null) {
                throw new IllegalStateException("Could not find the classLoaderContext line.");
            }
        }
        // The context might be empty if we didn't have the chance to update it after a version
        // upgrade. In this case return the special marker so that we recognize this is an unknown
        // context.
        return context == null ? UNKNOWN_CLASS_LOADER_CONTEXT : context;
    }

    /**
     * Reads the list of loading packages from the buffer {@code in} if
     * {@code version} is at least {PACKAGE_DEX_USAGE_SUPPORTED_VERSION_2}.
     */
    private List<String> maybeReadLoadingPackages(BufferedReader in, int version)
            throws IOException {
        if (version >= PACKAGE_DEX_USAGE_SUPPORTED_VERSION_2) {
            String line = in.readLine();
            if (line == null) {
                throw new IllegalStateException("Could not find the loadingPackages line.");
            }
            // We expect that most of the times the list of loading packages will be empty.
            if (line.length() == LOADING_PACKAGE_CHAR.length()) {
                return Collections.emptyList();
            } else {
                return Arrays.asList(
                        line.substring(LOADING_PACKAGE_CHAR.length()).split(SPLIT_CHAR));
            }
        } else {
            return Collections.emptyList();
        }
    }

    /**
     * Utility method which adds {@param loadingPackage} to {@param loadingPackages} only if it's
     * not equal to {@param owningPackage}
     */
    private boolean maybeAddLoadingPackage(String owningPackage, String loadingPackage,
            Set<String> loadingPackages) {
        return !owningPackage.equals(loadingPackage) && loadingPackages.add(loadingPackage);
    }

    private boolean isSupportedVersion(int version) {
        return version == PACKAGE_DEX_USAGE_VERSION ||
                version == PACKAGE_DEX_USAGE_SUPPORTED_VERSION_1;
    }

    /**
     * Syncs the existing data with the set of available packages by removing obsolete entries.
     */
    public void syncData(Map<String, Set<Integer>> packageToUsersMap) {
        synchronized (mPackageUseInfoMap) {
            Iterator<Map.Entry<String, PackageUseInfo>> pIt =
                    mPackageUseInfoMap.entrySet().iterator();
            while (pIt.hasNext()) {
                Map.Entry<String, PackageUseInfo> pEntry = pIt.next();
                String packageName = pEntry.getKey();
                PackageUseInfo packageUseInfo = pEntry.getValue();
                Set<Integer> users = packageToUsersMap.get(packageName);
                if (users == null) {
                    // The package doesn't exist anymore, remove the record.
                    pIt.remove();
                } else {
                    // The package exists but we can prune the entries associated with non existing
                    // users.
                    Iterator<Map.Entry<String, DexUseInfo>> dIt =
                            packageUseInfo.mDexUseInfoMap.entrySet().iterator();
                    while (dIt.hasNext()) {
                        DexUseInfo dexUseInfo = dIt.next().getValue();
                        if (!users.contains(dexUseInfo.mOwnerUserId)) {
                            // User was probably removed. Delete its dex usage info.
                            dIt.remove();
                        }
                    }
                    if (!packageUseInfo.mIsUsedByOtherApps
                            && packageUseInfo.mDexUseInfoMap.isEmpty()) {
                        // The package is not used by other apps and we removed all its dex files
                        // records. Remove the entire package record as well.
                        pIt.remove();
                    }
                }
            }
        }
    }

    /**
     * Clears the {@code usesByOtherApps} marker for the package {@code packageName}.
     * @return true if the package usage info was updated.
     */
    public boolean clearUsedByOtherApps(String packageName) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null || !packageUseInfo.mIsUsedByOtherApps) {
                return false;
            }
            packageUseInfo.mIsUsedByOtherApps = false;
            return true;
        }
    }

    /**
     * Remove the usage data associated with package {@code packageName}.
     * @return true if the package usage was found and removed successfully.
     */
    public boolean removePackage(String packageName) {
        synchronized (mPackageUseInfoMap) {
            return mPackageUseInfoMap.remove(packageName) != null;
        }
    }

    /**
     * Remove all the records about package {@code packageName} belonging to user {@code userId}.
     * If the package is left with no records of secondary dex usage and is not used by other
     * apps it will be removed as well.
     * @return true if the record was found and actually deleted,
     *         false if the record doesn't exist
     */
    public boolean removeUserPackage(String packageName, int userId) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            boolean updated = false;
            Iterator<Map.Entry<String, DexUseInfo>> dIt =
                            packageUseInfo.mDexUseInfoMap.entrySet().iterator();
            while (dIt.hasNext()) {
                DexUseInfo dexUseInfo = dIt.next().getValue();
                if (dexUseInfo.mOwnerUserId == userId) {
                    dIt.remove();
                    updated = true;
                }
            }
            // If no secondary dex info is left and the package is not used by other apps
            // remove the data since it is now useless.
            if (packageUseInfo.mDexUseInfoMap.isEmpty() && !packageUseInfo.mIsUsedByOtherApps) {
                mPackageUseInfoMap.remove(packageName);
                updated = true;
            }
            return updated;
        }
    }

    /**
     * Remove the secondary dex file record belonging to the package {@code packageName}
     * and user {@code userId}.
     * @return true if the record was found and actually deleted,
     *         false if the record doesn't exist
     */
    public boolean removeDexFile(String packageName, String dexFile, int userId) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            return removeDexFile(packageUseInfo, dexFile, userId);
        }
    }

    private boolean removeDexFile(PackageUseInfo packageUseInfo, String dexFile, int userId) {
        DexUseInfo dexUseInfo = packageUseInfo.mDexUseInfoMap.get(dexFile);
        if (dexUseInfo == null) {
            return false;
        }
        if (dexUseInfo.mOwnerUserId == userId) {
            packageUseInfo.mDexUseInfoMap.remove(dexFile);
            return true;
        }
        return false;
    }

    public PackageUseInfo getPackageUseInfo(String packageName) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo useInfo = mPackageUseInfoMap.get(packageName);
            // The useInfo contains a map for secondary dex files which could be modified
            // concurrently after this method returns and thus outside the locking we do here.
            // (i.e. the map is updated when new class loaders are created, which can happen anytime
            // after this method returns)
            // Make a defensive copy to be sure we don't get concurrent modifications.
            return useInfo == null ? null : new PackageUseInfo(useInfo);
        }
    }

    /**
     * Return all packages that contain records of secondary dex files.
     */
    public Set<String> getAllPackagesWithSecondaryDexFiles() {
        Set<String> packages = new HashSet<>();
        synchronized (mPackageUseInfoMap) {
            for (Map.Entry<String, PackageUseInfo> entry : mPackageUseInfoMap.entrySet()) {
                if (!entry.getValue().mDexUseInfoMap.isEmpty()) {
                    packages.add(entry.getKey());
                }
            }
        }
        return packages;
    }

    public void clear() {
        synchronized (mPackageUseInfoMap) {
            mPackageUseInfoMap.clear();
        }
    }
    // Creates a deep copy of the class' mPackageUseInfoMap.
    private Map<String, PackageUseInfo> clonePackageUseInfoMap() {
        Map<String, PackageUseInfo> clone = new HashMap<>();
        synchronized (mPackageUseInfoMap) {
            for (Map.Entry<String, PackageUseInfo> e : mPackageUseInfoMap.entrySet()) {
                clone.put(e.getKey(), new PackageUseInfo(e.getValue()));
            }
        }
        return clone;
    }

    private String writeBoolean(boolean bool) {
        return bool ? "1" : "0";
    }

    private boolean readBoolean(String bool) {
        if ("0".equals(bool)) return false;
        if ("1".equals(bool)) return true;
        throw new IllegalArgumentException("Unknown bool encoding: " + bool);
    }

    private boolean contains(int[] array, int elem) {
        for (int i = 0; i < array.length; i++) {
            if (elem == array[i]) {
                return true;
            }
        }
        return false;
    }

    public String dump() {
        StringWriter sw = new StringWriter();
        write(sw);
        return sw.toString();
    }

    /**
     * Stores data on how a package and its dex files are used.
     */
    public static class PackageUseInfo {
        // This flag is for the primary and split apks. It is set to true whenever one of them
        // is loaded by another app.
        private boolean mIsUsedByOtherApps;
        // Map dex paths to their data (isUsedByOtherApps, owner id, loader isa).
        private final Map<String, DexUseInfo> mDexUseInfoMap;
        // Packages who load this dex file.
        private final Set<String> mLoadingPackages;

        public PackageUseInfo() {
            mIsUsedByOtherApps = false;
            mDexUseInfoMap = new HashMap<>();
            mLoadingPackages = new HashSet<>();
        }

        // Creates a deep copy of the `other`.
        public PackageUseInfo(PackageUseInfo other) {
            mIsUsedByOtherApps = other.mIsUsedByOtherApps;
            mDexUseInfoMap = new HashMap<>();
            for (Map.Entry<String, DexUseInfo> e : other.mDexUseInfoMap.entrySet()) {
                mDexUseInfoMap.put(e.getKey(), new DexUseInfo(e.getValue()));
            }
            mLoadingPackages = new HashSet<>(other.mLoadingPackages);
        }

        private boolean merge(boolean isUsedByOtherApps) {
            boolean oldIsUsedByOtherApps = mIsUsedByOtherApps;
            mIsUsedByOtherApps = mIsUsedByOtherApps || isUsedByOtherApps;
            return oldIsUsedByOtherApps != this.mIsUsedByOtherApps;
        }

        public boolean isUsedByOtherApps() {
            return mIsUsedByOtherApps;
        }

        public Map<String, DexUseInfo> getDexUseInfoMap() {
            return mDexUseInfoMap;
        }

        public Set<String> getLoadingPackages() {
            return mLoadingPackages;
        }
    }

    /**
     * Stores data about a loaded dex files.
     */
    public static class DexUseInfo {
        private boolean mIsUsedByOtherApps;
        private final int mOwnerUserId;
        // The class loader context for the dex file. This encodes the class loader chain
        // (class loader type + class path) in a format compatible to dex2oat.
        // See {@code DexoptUtils.processContextForDexLoad}.
        private String mClassLoaderContext;
        // The instructions sets of the applications loading the dex file.
        private final Set<String> mLoaderIsas;
        // Packages who load this dex file.
        private final Set<String> mLoadingPackages;

        public DexUseInfo(boolean isUsedByOtherApps, int ownerUserId, String classLoaderContext,
                String loaderIsa) {
            mIsUsedByOtherApps = isUsedByOtherApps;
            mOwnerUserId = ownerUserId;
            mClassLoaderContext = classLoaderContext;
            mLoaderIsas = new HashSet<>();
            if (loaderIsa != null) {
                mLoaderIsas.add(loaderIsa);
            }
            mLoadingPackages = new HashSet<>();
        }

        // Creates a deep copy of the `other`.
        public DexUseInfo(DexUseInfo other) {
            mIsUsedByOtherApps = other.mIsUsedByOtherApps;
            mOwnerUserId = other.mOwnerUserId;
            mClassLoaderContext = other.mClassLoaderContext;
            mLoaderIsas = new HashSet<>(other.mLoaderIsas);
            mLoadingPackages = new HashSet<>(other.mLoadingPackages);
        }

        private boolean merge(DexUseInfo dexUseInfo) {
            boolean oldIsUsedByOtherApps = mIsUsedByOtherApps;
            mIsUsedByOtherApps = mIsUsedByOtherApps || dexUseInfo.mIsUsedByOtherApps;
            boolean updateIsas = mLoaderIsas.addAll(dexUseInfo.mLoaderIsas);
            boolean updateLoadingPackages = mLoadingPackages.addAll(dexUseInfo.mLoadingPackages);

            String oldClassLoaderContext = mClassLoaderContext;
            if (UNKNOWN_CLASS_LOADER_CONTEXT.equals(mClassLoaderContext)) {
                // Can happen if we read a previous version.
                mClassLoaderContext = dexUseInfo.mClassLoaderContext;
            } else if (UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(dexUseInfo.mClassLoaderContext)) {
                // We detected an unsupported context.
                mClassLoaderContext = UNSUPPORTED_CLASS_LOADER_CONTEXT;
            } else if (!UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(mClassLoaderContext) &&
                    !Objects.equal(mClassLoaderContext, dexUseInfo.mClassLoaderContext)) {
                // We detected a context change.
                mClassLoaderContext = VARIABLE_CLASS_LOADER_CONTEXT;
            }

            return updateIsas ||
                    (oldIsUsedByOtherApps != mIsUsedByOtherApps) ||
                    updateLoadingPackages
                    || !Objects.equal(oldClassLoaderContext, mClassLoaderContext);
        }

        public boolean isUsedByOtherApps() {
            return mIsUsedByOtherApps;
        }

        public int getOwnerUserId() {
            return mOwnerUserId;
        }

        public Set<String> getLoaderIsas() {
            return mLoaderIsas;
        }

        public Set<String> getLoadingPackages() {
            return mLoadingPackages;
        }

        public String getClassLoaderContext() { return mClassLoaderContext; }

        public boolean isUnsupportedClassLoaderContext() {
            return UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(mClassLoaderContext);
        }

        public boolean isUnknownClassLoaderContext() {
            // The class loader context may be unknown if we loaded the data from a previous version
            // which didn't save the context.
            return UNKNOWN_CLASS_LOADER_CONTEXT.equals(mClassLoaderContext);
        }

        public boolean isVariableClassLoaderContext() {
            return VARIABLE_CLASS_LOADER_CONTEXT.equals(mClassLoaderContext);
        }
    }
}
