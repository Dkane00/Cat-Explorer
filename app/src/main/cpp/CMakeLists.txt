# Native code for the tablet's own RTL-SDR panadapter.
#
#  libusb/   libusb 1.0.27 (LGPL 2.1), unchanged, from https://github.com/libusb/libusb
#  rtlsdr/   RTL-SDR Blog's librtlsdr (GPL 2+), from https://github.com/rtlsdrblog/rtl-sdr-blog at aed0ea1,
#            with one addition: rtlsdr_open_fd(), which opens a dongle Android has already opened for us
#  catsdr.c  Cat Explorer's bridge to Kotlin: open, tune, and averaged spectra
cmake_minimum_required(VERSION 3.22.1)
project(catsdr C)

set(LIBUSB ${CMAKE_CURRENT_SOURCE_DIR}/libusb)
add_library(usb1 STATIC
    ${LIBUSB}/core.c ${LIBUSB}/descriptor.c ${LIBUSB}/hotplug.c ${LIBUSB}/io.c ${LIBUSB}/sync.c ${LIBUSB}/strerror.c
    ${LIBUSB}/os/linux_usbfs.c ${LIBUSB}/os/events_posix.c ${LIBUSB}/os/threads_posix.c ${LIBUSB}/os/linux_netlink.c)
target_include_directories(usb1 PRIVATE ${LIBUSB}/android ${LIBUSB} ${LIBUSB}/os PUBLIC ${LIBUSB})
target_compile_options(usb1 PRIVATE -fvisibility=hidden -pthread -Wno-everything)

set(RTL ${CMAKE_CURRENT_SOURCE_DIR}/rtlsdr)
add_library(rtlsdr STATIC
    ${RTL}/librtlsdr.c ${RTL}/tuner_e4k.c ${RTL}/tuner_fc0012.c ${RTL}/tuner_fc0013.c ${RTL}/tuner_fc2580.c ${RTL}/tuner_r82xx.c)
target_include_directories(rtlsdr PUBLIC ${RTL}/include)
target_compile_definitions(rtlsdr PRIVATE rtlsdr_STATIC)
target_compile_options(rtlsdr PRIVATE -Wno-everything)
target_link_libraries(rtlsdr usb1)

add_library(catsdr SHARED catsdr.c)
target_compile_definitions(catsdr PRIVATE rtlsdr_STATIC)
target_compile_options(catsdr PRIVATE -O2)
target_link_libraries(catsdr rtlsdr usb1 log m)
# Android 15+ devices can use 16 KB memory pages; native code must be laid out for them
target_link_options(catsdr PRIVATE "-Wl,-z,max-page-size=16384")
