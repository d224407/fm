package com.example.fm;

import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ZipAlign;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlElement;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** APK editing on top of ARSCLib (bundled in libs/apkeditor.jar): quick manifest edit and anti-split merge. */
public final class ApkOps {
    private ApkOps() {}

    /** Manifest attributes shown in the quick-edit dialog; null = leave unchanged when writing. */
    public static final class Info {
        public String pkg;
        public String label;
        public String versionName;
        public Integer versionCode;
        public Integer minSdk;
        public Integer targetSdk;
    }

    public static Info read(File apk) throws IOException {
        try (ApkModule m = ApkModule.loadApkFile(apk)) {
            AndroidManifestBlock mf = m.getAndroidManifest();
            if (mf == null) throw new IOException("APK không có AndroidManifest");
            Info i = new Info();
            i.pkg = mf.getPackageName();
            i.label = mf.getApplicationLabelString();
            i.versionName = mf.getVersionName();
            i.versionCode = mf.getVersionCode();
            i.minSdk = mf.getMinSdkVersion();
            i.targetSdk = mf.getTargetSdkVersion();
            return i;
        }
    }

    /** Writes a zip-aligned, still unsigned copy of [in] with the changed attributes. */
    public static void edit(File in, File out, Info n, File tmp) throws IOException {
        try (ApkModule m = ApkModule.loadApkFile(in)) {
            AndroidManifestBlock mf = m.getAndroidManifest();
            if (mf == null) throw new IOException("APK không có AndroidManifest");
            if (n.pkg != null) mf.setPackageName(n.pkg);
            if (n.label != null) mf.setApplicationLabel(n.label);
            if (n.versionName != null) mf.setVersionName(n.versionName);
            if (n.versionCode != null) mf.setVersionCode(n.versionCode);
            if (n.minSdk != null) mf.setMinSdkVersion(n.minSdk);
            if (n.targetSdk != null) mf.setTargetSdkVersion(n.targetSdk);
            mf.refresh();
            m.writeApk(tmp);
        }
        ZipAlign.alignApk(tmp, out);
    }

    /** Merges the split APKs found in [dir] into one unsigned, zip-aligned APK. */
    public static void merge(File dir, File out, File tmp) throws IOException {
        try (ApkBundle bundle = new ApkBundle()) {
            bundle.loadApkDirectory(dir, false);
            try (ApkModule m = bundle.mergeModules(false)) {
                sanitize(m);
                m.writeApk(tmp);
            }
        }
        ZipAlign.alignApk(tmp, out);
    }

    private static void stripSplitAttrs(ResXmlElement e) {
        e.removeAttributesWithId(AndroidManifest.ID_requiredSplitTypes);
        e.removeAttributesWithId(AndroidManifest.ID_splitTypes);
        e.removeAttributesWithId(AndroidManifest.ID_isSplitRequired);
        e.removeAttributesWithName(AndroidManifest.NAME_requiredSplitTypes);
        e.removeAttributesWithName(AndroidManifest.NAME_splitTypes);
        e.removeAttributesWithName(AndroidManifest.NAME_isSplitRequired);
    }

    /** Drops everything that tells the installer more splits are required. */
    private static void sanitize(ApkModule m) {
        if (!m.hasAndroidManifest()) return;
        AndroidManifestBlock mf = m.getAndroidManifest();
        ResXmlElement root = mf.getManifestElement();
        if (root != null) stripSplitAttrs(root);
        ResXmlElement app = mf.getApplicationElement();
        if (app != null) {
            stripSplitAttrs(app);
            app.removeAttributesWithId(AndroidManifest.ID_extractNativeLibs);
            app.removeAttributesWithName(AndroidManifest.NAME_extractNativeLibs);
            List<ResXmlElement> kill = new ArrayList<>();
            for (Object o : app.listElements("meta-data")) {
                ResXmlElement e = (ResXmlElement) o;
                String name = AndroidManifestBlock.getAndroidNameValue(e);
                if (name != null && (name.startsWith("com.android.vending.splits")
                        || name.equals("com.android.vending.derived.apk.id"))) {
                    kill.add(e);
                }
            }
            for (ResXmlElement e : kill) app.remove(e);
        }
        mf.refresh();
    }
}
