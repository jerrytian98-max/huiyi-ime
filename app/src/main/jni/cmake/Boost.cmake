# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

set(BOOST_VERSION 1.92.0)
set(BOOST_ARCHIVE "${CMAKE_SOURCE_DIR}/../../../boost-${BOOST_VERSION}.tar.xz")

if(NOT EXISTS "${BOOST_ARCHIVE}")
  message(STATUS "Downloading Boost ${BOOST_VERSION} ......")
  file(
    DOWNLOAD
    "https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.xz"
    "${BOOST_ARCHIVE}.part"
    EXPECTED_HASH
      SHA256=9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d
    SHOW_PROGRESS STATUS BOOST_DOWNLOAD_STATUS TIMEOUT 300)
  list(GET BOOST_DOWNLOAD_STATUS 0 BOOST_DOWNLOAD_CODE)
  if(NOT BOOST_DOWNLOAD_CODE EQUAL 0)
    message(FATAL_ERROR "Boost download failed: ${BOOST_DOWNLOAD_STATUS}. Download the verified archive to ${BOOST_ARCHIVE} and retry.")
  endif()
  file(RENAME "${BOOST_ARCHIVE}.part" "${BOOST_ARCHIVE}")
endif()

file(SHA256 "${BOOST_ARCHIVE}" BOOST_ARCHIVE_HASH)
if(NOT BOOST_ARCHIVE_HASH STREQUAL "9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d")
  message(FATAL_ERROR "Boost archive checksum mismatch: ${BOOST_ARCHIVE}")
endif()

if(NOT EXISTS "${CMAKE_SOURCE_DIR}/boost")
  message(STATUS "Extracting Boost ${BOOST_VERSION} ......")
  file(ARCHIVE_EXTRACT INPUT "${BOOST_ARCHIVE}" DESTINATION
       ${CMAKE_SOURCE_DIR})
  file(RENAME "boost-${BOOST_VERSION}" boost)
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    preprocessor
    range
    regex
    scope_exit
    signals2
    utility
    uuid
    vmd)

add_subdirectory(boost EXCLUDE_FROM_ALL)
