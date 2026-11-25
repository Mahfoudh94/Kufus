Summary: APPLICATION_SUMMARY
Name: APPLICATION_PACKAGE
Version: APPLICATION_VERSION
Release: APPLICATION_RELEASE
License: APPLICATION_LICENSE_TYPE
Vendor: APPLICATION_VENDOR

%if "xAPPLICATION_URL" != "x"
URL: APPLICATION_URL
%endif

%if "xAPPLICATION_PREFIX" != "x"
Prefix: APPLICATION_PREFIX
%endif

Provides: APPLICATION_PACKAGE

%if "xAPPLICATION_GROUP" != "x"
Group: APPLICATION_GROUP
%endif

Autoprov: 0
Autoreq: 0
%if "xPACKAGE_DEFAULT_DEPENDENCIES" != "x" || "xPACKAGE_CUSTOM_DEPENDENCIES" != "x"
Requires: PACKAGE_DEFAULT_DEPENDENCIES PACKAGE_CUSTOM_DEPENDENCIES
%endif

#comment line below to enable effective jar compression
#it could easily get your package size from 40 to 15Mb but
#build time will substantially increase and it may require unpack200/system java to install
%define __jar_repack %{nil}

# on RHEL we got unwanted improved debugging enhancements
%define _build_id_links none

%define package_filelist %{_builddir}/%{name}.files
%define app_filelist %{_builddir}/%{name}.app.files
%define filesystem_filelist %{_builddir}/%{name}.filesystem.files

%define default_filesystem / /opt /usr /usr/bin /usr/lib /usr/local /usr/local/bin /usr/local/lib

%description
APPLICATION_DESCRIPTION

%global __os_install_post %{nil}

%prep

%build

%install
rm -rf %{buildroot}
install -d -m 755 %{buildroot}APPLICATION_DIRECTORY
cp -r %{_sourcedir}APPLICATION_DIRECTORY/* %{buildroot}APPLICATION_DIRECTORY
if [ "$(echo %{_sourcedir}/lib/systemd/system/*.service)" != '%{_sourcedir}/lib/systemd/system/*.service' ]; then
  install -d -m 755 %{buildroot}/lib/systemd/system
  cp %{_sourcedir}/lib/systemd/system/*.service %{buildroot}/lib/systemd/system
fi
for desktop_src in %{buildroot}APPLICATION_DIRECTORY/lib/*.desktop; do
  if [ -f "$desktop_src" ]; then
    install -d -m 755 "%{buildroot}/usr/share/applications"
    install -m 644 "$desktop_src" "%{buildroot}/usr/share/applications/APPLICATION_PACKAGE.desktop"
    sed -i 's|^Icon=.*|Icon=APPLICATION_PACKAGE|' "%{buildroot}/usr/share/applications/APPLICATION_PACKAGE.desktop"
    break
  fi
done
for icon_src in %{buildroot}APPLICATION_DIRECTORY/lib/*.png; do
  if [ -f "$icon_src" ]; then
    install -d -m 755 "%{buildroot}/usr/share/icons/hicolor/256x256/apps"
    install -m 644 "$icon_src" "%{buildroot}/usr/share/icons/hicolor/256x256/apps/APPLICATION_PACKAGE.png"
    break
  fi
done
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %define license_install_file %{_defaultlicensedir}/%{name}-%{version}/%{basename:APPLICATION_LICENSE_FILE}
  install -d -m 755 "%{buildroot}%{dirname:%{license_install_file}}"
  install -m 644 "APPLICATION_LICENSE_FILE" "%{buildroot}%{license_install_file}"
%endif
(cd %{buildroot} && find . -path ./lib/systemd -prune -o -path ./usr -prune -o -type d -print) | sed -e 's/^\.//' -e '/^$/d' | sort > %{app_filelist}
{ rpm -ql filesystem 2>/dev/null || printf '%s\n' %{default_filesystem}; } | sort > %{filesystem_filelist}
comm -23 %{app_filelist} %{filesystem_filelist} > %{package_filelist}
sed -i -e 's/.*/%dir "&"/' %{package_filelist}
(cd %{buildroot} && find . -not -type d) | sed -e 's/^\.//' -e 's/.*/"&"/' >> %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  sed -i -e 's|"%{license_install_file}"||' -e '/^$/d' %{package_filelist}
%endif

%files -f %{package_filelist}
%if "xAPPLICATION_LICENSE_FILE" != "x"
  %license "%{license_install_file}"
%endif

%post
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
LAUNCHER_AS_SERVICE_COMMANDS_INSTALL
update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || true
touch --no-create /usr/share/icons/hicolor >/dev/null 2>&1 || true
gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true

%pre
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
if [ "$1" = 2 ]; then
  true; LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL
fi

%preun
package_type=rpm
LAUNCHER_AS_SERVICE_SCRIPTS
if [ "$1" = 0 ]; then
  true; LAUNCHER_AS_SERVICE_COMMANDS_UNINSTALL
fi

%postun
update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || true
if [ "$1" -eq 0 ]; then
  touch --no-create /usr/share/icons/hicolor >/dev/null 2>&1 || true
  gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true
fi

%posttrans
# Remove legacy xdg-desktop-menu entry left or conflicted by earlier packages, then refresh desktop caches
rm -f /usr/local/share/applications/APPLICATION_PACKAGE-*.desktop >/dev/null 2>&1 || true
update-desktop-database -q /usr/local/share/applications >/dev/null 2>&1 || true
update-desktop-database -q /usr/share/applications >/dev/null 2>&1 || true
touch --no-create /usr/share/icons/hicolor >/dev/null 2>&1 || true
gtk-update-icon-cache -f -t /usr/share/icons/hicolor >/dev/null 2>&1 || true

%clean
