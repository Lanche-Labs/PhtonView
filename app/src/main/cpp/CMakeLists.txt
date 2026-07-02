cmake_minimum_required(VERSION 3.22.1)
project("phtonview")

# libgphoto2 预编译库路径（通过 build.gradle.kts 传入）
set(GPHOTO2_ROOT "${CMAKE_SOURCE_DIR}/../../gphoto2/prebuilt" CACHE PATH "libgphoto2 prebuilt root")

if(EXISTS ${GPHOTO2_ROOT}/include/gphoto2/gphoto2.h)
    message(STATUS "libgphoto2 prebuilt found at ${GPHOTO2_ROOT}")
    include_directories(
        ${GPHOTO2_ROOT}/include
        ${GPHOTO2_ROOT}/include/gphoto2
        ${GPHOTO2_ROOT}/include/libusb-1.0
    )

    # 导入 libgphoto2 核心库
    add_library(gphoto2 SHARED IMPORTED)
    set_target_properties(gphoto2 PROPERTIES
        IMPORTED_LOCATION ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libgphoto2.so)

    add_library(gphoto2port SHARED IMPORTED)
    set_target_properties(gphoto2port PROPERTIES
        IMPORTED_LOCATION ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libgphoto2_port.so)

    # libtool 的动态加载器
    add_library(ltdl SHARED IMPORTED)
    set_target_properties(ltdl PROPERTIES
        IMPORTED_LOCATION ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libltdl.so)

    # libusb-1.0（gphoto2_port USB 后端使用）
    add_library(usb1 SHARED IMPORTED)
    set_target_properties(usb1 PROPERTIES
        IMPORTED_LOCATION ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libusb-1.0.so)

    # libusb 0.1 兼容层（部分 gphoto2 后端通过它链接）
    if(EXISTS ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libusb-0.1.so)
        add_library(usb01 SHARED IMPORTED)
        set_target_properties(usb01 PROPERTIES
            IMPORTED_LOCATION ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libusb-0.1.so)
    elseif(EXISTS ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libusb.so)
        add_library(usb01 SHARED IMPORTED)
        set_target_properties(usb01 PROPERTIES
            IMPORTED_LOCATION ${GPHOTO2_ROOT}/${ANDROID_ABI}/lib/libusb.so)
    endif()

    add_definitions(-DHAVE_GPHOTO2)
else()
    message(STATUS "libgphoto2 prebuilt NOT found at ${GPHOTO2_ROOT}; building stub bridge only")
endif()

add_library(
    phtonview
    SHARED
    native-lib.cpp
    gphoto2_bridge.cpp)

find_library(log-lib log)

if(EXISTS ${GPHOTO2_ROOT}/include/gphoto2/gphoto2.h)
    # 单独编译一个仅导出 libltdl 预加载符号表的 helper so，
    # 确保 libltdl.so 在加载时能找到缺失的 lt_libltdl_LTX_preloaded_symbols。
    add_library(
        ltdl_preload
        SHARED
        ltdl_preload.cpp)
    target_include_directories(ltdl_preload PRIVATE ${GPHOTO2_ROOT}/include)
    target_link_libraries(ltdl_preload ${log-lib})

    target_link_libraries(phtonview
        gphoto2
        gphoto2port
        usb1
        usb01
        ltdl
        ${log-lib})
else()
    target_link_libraries(phtonview ${log-lib})
endif()
