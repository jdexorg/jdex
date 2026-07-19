# PackageManager implementation script example.

emu = jdex.this.emu

PACKAGE_NAME  = jdex.app_package()
VERSION_NAME  = "1.0"
VERSION_CODE  = 1
SIGNATURES    = [b"\x30\x82\x01\x00"]
INSTALLER     = "com.android.vending"
PERMISSIONS   = jdex.permissions()

sigs = [emu.new("Landroid/content/pm/Signature;", "([B)V", b) for b in SIGNATURES]

pinfo = emu.new("Landroid/content/pm/PackageInfo;")
pinfo.set("packageName", PACKAGE_NAME)
pinfo.set("versionName", VERSION_NAME)
pinfo.set("versionCode", VERSION_CODE)
pinfo.set("signatures", sigs)

ainfo = emu.new("Landroid/content/pm/ApplicationInfo;")
ainfo.set("packageName", PACKAGE_NAME)
pinfo.set("applicationInfo", ainfo)

pm = emu.new("Landroid/content/pm/PackageManager;")

emu.register_stub("Landroid/content/Context;", "getPackageManager", lambda r, a: pm)
emu.register_stub("Landroid/content/Context;", "getPackageName",    lambda r, a: PACKAGE_NAME)

def get_package_info(r, a):
    return pinfo

def get_application_info(r, a):
    return ainfo

def get_installer_package_name(r, a):
    return INSTALLER

def check_permission(r, a):
    return 0 if a and a[0] in PERMISSIONS else -1

emu.register_stub("Landroid/content/pm/PackageManager;", "getPackageInfo",            get_package_info)
emu.register_stub("Landroid/content/pm/PackageManager;", "getApplicationInfo",        get_application_info)
emu.register_stub("Landroid/content/pm/PackageManager;", "getInstallerPackageName",   get_installer_package_name)
emu.register_stub("Landroid/content/pm/PackageManager;", "checkPermission",           check_permission)

emu.register_stub("Landroid/content/pm/Signature;", "toByteArray", lambda r, a: SIGNATURES[0])
emu.register_stub("Landroid/content/pm/Signature;", "hashCode",    lambda r, a: 0)

jdex.ui.message("PackageManager implemented")
