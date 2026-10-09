# SPDX-License-Identifier: Unlicense
#
# neutrodyne.spec — the RPM spec jpackage builds for Neutrodyne (11 Linux DEB, RPM and
# tar.gz). The file must be named <linux-package-name>.spec: jpackage's --resource-dir
# lookup resolves a resource by the name of the file it is about to write —
# SPECS/neutrodyne.spec — so a template.spec here is silently ignored (nightly
# 37860407043 built the RPM with jpackage's default spec and none of the Requires
# below). Written from the RPM spec format and jpackage's documented substitutions —
# jpackage's own template is GPL-2.0+CE and never copied (D3). jpackage replaces the
# APPLICATION_* tokens and points %_sourcedir at the staged install tree.

Summary: APPLICATION_SUMMARY
Name: APPLICATION_PACKAGE
Version: APPLICATION_VERSION
Release: APPLICATION_RELEASE
License: APPLICATION_LICENSE_TYPE
Vendor: APPLICATION_VENDOR
Provides: APPLICATION_PACKAGE

# The payload is prebuilt: never scan it for Provides/Requires (Autoprov/Autoreq), never
# make a -debuginfo package or build-id links, and run no post-install scripts (they
# would strip or repack the bundled runtime's files).
Autoprov: 0
Autoreq: 0
%define debug_package %{nil}
%define _build_id_links none
%define __jar_repack %{nil}
%global __os_install_post %{nil}

# The portable soname Requires of 11: shared libraries the JARs' natives and the JVM need
# at run time; both Linux targets are 64-bit ELF, so the (64bit) marker fits x64 and arm64.
Requires: libc.so.6()(64bit)
Requires: libasound.so.2()(64bit)
Requires: libX11.so.6()(64bit)
Requires: libXext.so.6()(64bit)
Requires: libXi.so.6()(64bit)
Requires: libXrender.so.6()(64bit)
Requires: libXtst.so.6()(64bit)
Requires: libfreetype.so.6()(64bit)
Requires: libfontconfig.so.1()(64bit)
Requires: libz.so.1()(64bit)

%if "xAPPLICATION_GROUP" != "x"
Group: APPLICATION_GROUP
%endif
%if "xAPPLICATION_URL" != "x"
URL: APPLICATION_URL
%endif
%if "xAPPLICATION_PREFIX" != "x"
Prefix: APPLICATION_PREFIX
%endif

%description
APPLICATION_DESCRIPTION

%prep

%build

%install
rm -rf %{buildroot}
install -d -m 755 %{buildroot}APPLICATION_DIRECTORY
cp -r %{_sourcedir}APPLICATION_DIRECTORY/* %{buildroot}APPLICATION_DIRECTORY
%if "xAPPLICATION_LICENSE_FILE" != "x"
install -d -m 755 "%{buildroot}%{_defaultlicensedir}/%{name}-%{version}"
install -m 644 "APPLICATION_LICENSE_FILE" "%{buildroot}%{_defaultlicensedir}/%{name}-%{version}/"
%endif

%files
APPLICATION_DIRECTORY
%if "xAPPLICATION_LICENSE_FILE" != "x"
%license %{_defaultlicensedir}/%{name}-%{version}
%endif

# The same desktop integration the DEB's maintainer scripts perform (11 Links and files
# from the OS): the frozen desktop entry and the hicolor icons ship inside the image.
%post
set -e
APP_DIR="APPLICATION_DIRECTORY"
INTEGRATION_DIR="$APP_DIR/lib/app/resources/desktop-integration"
APPLICATIONS_DIR="/usr/share/applications"
ICONS_DIR="/usr/share/icons/hicolor"

install -D -m 0644 "$INTEGRATION_DIR/ch.lkmc.neutrodyne.desktop" \
    "$APPLICATIONS_DIR/ch.lkmc.neutrodyne.desktop"

# The install set matches what %postun removes: every icon is installed as neutrodyne.png.
find "$INTEGRATION_DIR/hicolor" -type f -name 'neutrodyne.png' | while IFS= read -r icon; do
    rel="${icon#"$INTEGRATION_DIR/hicolor/"}"
    install -D -m 0644 "$icon" "$ICONS_DIR/$rel"
done

command -v update-desktop-database >/dev/null 2>&1 && \
    update-desktop-database "$APPLICATIONS_DIR" || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && \
    gtk-update-icon-cache -q -t -f "$ICONS_DIR" || true

exit 0

# %postun's $1 is the number of packages left installed after this run: remove the
# integration only on a real erase (0), never on an upgrade.
%postun
set -e
if [ "$1" != "0" ]; then
    exit 0
fi

APPLICATIONS_DIR="/usr/share/applications"
ICONS_DIR="/usr/share/icons/hicolor"

rm -f "$APPLICATIONS_DIR/ch.lkmc.neutrodyne.desktop"
find "$ICONS_DIR" -type f -name 'neutrodyne.png' -exec rm -f {} +

command -v update-desktop-database >/dev/null 2>&1 && \
    update-desktop-database "$APPLICATIONS_DIR" || true
command -v gtk-update-icon-cache >/dev/null 2>&1 && \
    gtk-update-icon-cache -q -t -f "$ICONS_DIR" || true

exit 0
