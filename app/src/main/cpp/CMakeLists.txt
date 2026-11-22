# Copyright (C) 2026 David Kane (W6MZT)
# SPDX-License-Identifier: GPL-3.0-or-later
# Native code for the tablet's own RTL-SDR panadapter.
#
#  libusb/   libusb 1.0.27 (LGPL 2.1), unchanged, from https://github.com/libusb/libusb
#  rtlsdr/   RTL-SDR Blog's librtlsdr (GPL 2+), from https://github.com/rtlsdrblog/rtl-sdr-blog at aed0ea1,
#            with one addition: rtlsdr_open_fd(), which opens a dongle Android has already opened for us
#  airspy/   libairspy 1.0.12 (BSD), from https://github.com/airspy/airspyone_host at fc61ab6, unchanged
#  airspyhf/ libairspyhf 1.8.1 (BSD), from https://github.com/airspy/airspyhf at 24fe8ff, unchanged
#  hackrf/   libhackrf (BSD), from https://github.com/greatscottgadgets/hackrf at 8540a76, with one addition: hackrf_open_fd()
#  catsdr.c  Cat Explorer's bridge to Kotlin: open, tune, gain, and averaged spectra
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

set(AIRSPY ${CMAKE_CURRENT_SOURCE_DIR}/airspy)
add_library(airspy STATIC ${AIRSPY}/airspy.c ${AIRSPY}/iqconverter_float.c ${AIRSPY}/iqconverter_int16.c)
target_include_directories(airspy PUBLIC ${AIRSPY})
target_compile_options(airspy PRIVATE -Wno-everything)
target_link_libraries(airspy usb1)

set(AIRSPYHF ${CMAKE_CURRENT_SOURCE_DIR}/airspyhf)
add_library(airspyhf STATIC ${AIRSPYHF}/airspyhf.c ${AIRSPYHF}/iqbalancer.c)
target_include_directories(airspyhf PUBLIC ${AIRSPYHF})
target_compile_options(airspyhf PRIVATE -Wno-everything)
target_link_libraries(airspyhf usb1)

set(HACKRF ${CMAKE_CURRENT_SOURCE_DIR}/hackrf)
add_library(hackrf STATIC ${HACKRF}/hackrf.c)
target_include_directories(hackrf PUBLIC ${HACKRF})
target_compile_definitions(hackrf PRIVATE LIBRARY_VERSION="2026.01" LIBRARY_RELEASE="cat-explorer")
target_compile_options(hackrf PRIVATE -Wno-everything)
target_link_libraries(hackrf usb1)

add_library(catsdr SHARED catsdr.c)
target_compile_definitions(catsdr PRIVATE rtlsdr_STATIC)
target_compile_options(catsdr PRIVATE -O2)
target_link_libraries(catsdr rtlsdr airspy airspyhf hackrf usb1 log m)
# Android 15+ devices can use 16 KB memory pages; native code must be laid out for them
target_link_options(catsdr PRIVATE "-Wl,-z,max-page-size=16384")
