# Copyright (c) 2026 Marco Morosi
#
# Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
# the European Commission - subsequent versions of the EUPL (the "Licence");
# You may not use this work except in compliance with the Licence.
# You may obtain a copy of the Licence at:
#
# https://joinup.ec.europa.eu/software/page/eupl
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the Licence is distributed on an "AS IS" basis,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the Licence for the specific language governing permissions and
# limitations under the Licence.
#
# wfas3_verify_manifest(base manifest dirs): every line "sha256  ./path" of
# the manifest must name a file under base with that SHA-256, and every file
# under base/<dir> for each dir must be listed. Lines starting with # are
# comments. Any difference stops the configuration.

function(wfas3_verify_manifest base manifest dirs)
    if(NOT EXISTS ${manifest})
        message(FATAL_ERROR "wfas3: manifest missing: ${manifest}")
    endif()
    file(STRINGS ${manifest} lines)
    set(listed "")
    foreach(line IN LISTS lines)
        if(line MATCHES "^#" OR line STREQUAL "")
            continue()
        endif()
        if(NOT line MATCHES "^([0-9a-f]+)  \\./(.+)$")
            message(FATAL_ERROR "wfas3: malformed manifest line in ${manifest}: ${line}")
        endif()
        set(want ${CMAKE_MATCH_1})
        set(rel ${CMAKE_MATCH_2})
        if(NOT EXISTS ${base}/${rel})
            message(FATAL_ERROR "wfas3: pinned file missing: ${rel}")
        endif()
        file(SHA256 ${base}/${rel} got)
        if(NOT got STREQUAL want)
            message(FATAL_ERROR "wfas3: pinned file changed: ${rel} (check line endings: these files must be checked out byte for byte)")
        endif()
        list(APPEND listed ${rel})
    endforeach()
    foreach(dir IN LISTS dirs)
        if(dir STREQUAL ".")
            file(GLOB_RECURSE present RELATIVE ${base} ${base}/*)
        else()
            file(GLOB_RECURSE present RELATIVE ${base} ${base}/${dir}/*)
        endif()
        foreach(f IN LISTS present)
            get_filename_component(fname ${f} NAME)
            if(fname STREQUAL "MANIFEST.sha256" AND dir STREQUAL ".")
                continue()
            endif()
            list(FIND listed ${f} idx)
            if(idx EQUAL -1)
                message(FATAL_ERROR "wfas3: file not in manifest: ${f}")
            endif()
        endforeach()
    endforeach()
    message(STATUS "wfas3: ${manifest}: all pinned files verified")
endfunction()
