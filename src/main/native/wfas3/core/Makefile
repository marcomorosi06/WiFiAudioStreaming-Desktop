# WFAS v3 - build and checks.
#
#   make            manifest check, third-party objects, core object
#   make check      everything below except mutate; any warning or failure stops it
#   make manifest   verify third_party/ against MANIFEST.sha256
#   make model      bounded model of the pair state machines (specification 9.5)
#   make interop    C core against the second implementation in tests/peer.py
#   make mutate     known defects put back one at a time: the suites must fail
#   make analyze    cppcheck (if installed) and the Clang static analyser
#   make vectors    regenerate tests/vectors.h (needs python3, cryptography, kyber-py)
#
# The core (wfas.c) is built with the options of the design document, with GCC
# and with Clang, warnings as errors. Third-party files are built unmodified
# with their own options. Python is needed only by the tests.

CC      ?= gcc
CLANG   ?= clang
PYTHON  ?= python3
B       := build

HACL    := third_party/hacl
FIAT    := third_party/fiat
TP_INC  := -I$(HACL)/include -I$(HACL)/karamel/include -I$(HACL)/karamel/krmllib/dist/minimal
HARDEN  := -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fPIE
TP_FLAGS := -std=gnu11 -O2 -D_DEFAULT_SOURCE $(HARDEN) -w
GLUE_FLAGS := -std=gnu11 -O2 $(HARDEN) -Wall -Wextra -Werror -Wno-unused-function -Wno-unused-parameter

CORE_FLAGS := -std=c99 -pedantic-errors -Werror -Wall -Wextra -Wconversion -Wsign-conversion \
  -Wshadow -Wcast-qual -Wcast-align -Wstrict-prototypes -Wmissing-prototypes \
  -Wvla -Wundef -Wswitch-enum -Wswitch-default -Wimplicit-fallthrough \
  -Wnull-dereference -ftrivial-auto-var-init=zero -O2 $(HARDEN)
TEST_FLAGS := -std=c99 -pedantic-errors -Werror -Wall -Wextra -Wconversion -Wsign-conversion -O2

HACL_SRC := $(wildcard $(HACL)/src/*.c)
HACL_OBJ := $(patsubst $(HACL)/src/%.c,$(B)/tp/%.o,$(HACL_SRC))
TP_OBJ   := $(HACL_OBJ) $(B)/tp/wfas_tp.o $(B)/tp/wfas_tp_mlkem.o
SAN      := -fsanitize=address,undefined -fno-sanitize-recover=all -g -O1
SAN_TP   := $(patsubst $(HACL)/src/%.c,$(B)/san/%.o,$(HACL_SRC)) $(B)/san/wfas_tp.o $(B)/san/wfas_tp_mlkem.o

CLI_FLAGS := -std=c99 -O2 -Wall -Wextra -Werror -Wconversion -Wsign-conversion -Wshadow -Wcast-qual \
  -Wstrict-prototypes -Wmissing-prototypes $(HARDEN) -pie

ASCII_FILES := wfas.c wfas.h wfas_tp.c wfas_tp.h wfas_tp_mlkem.c wfascli.c Makefile \
  WFAS_V3_PROTOCOL.md CHANGELOG.md NOTICE LICENSE tests/*.c tests/*.py model/*.py

all: manifest $(TP_OBJ) $(B)/wfas.o $(B)/wfascli

manifest:
	@cd third_party && grep -v '^#' MANIFEST.sha256 | sha256sum -c --quiet --strict
	@cd third_party && test "$$(find . -type f ! -name MANIFEST.sha256 | wc -l)" = \
	  "$$(grep -vc '^#' MANIFEST.sha256)" || { echo "third_party: file not in manifest"; exit 1; }
	@echo "third_party: manifest ok"

# -- normal objects ----------------------------------------------------------

$(B)/tp/%.o: $(HACL)/src/%.c | manifest
	@mkdir -p $(B)/tp
	$(CC) $(TP_FLAGS) $(TP_INC) -c $< -o $@

$(B)/tp/wfas_tp.o: wfas_tp.c wfas_tp.h | manifest
	@mkdir -p $(B)/tp
	$(CC) $(GLUE_FLAGS) $(TP_INC) -I$(FIAT) -c $< -o $@

$(B)/tp/wfas_tp_mlkem.o: wfas_tp_mlkem.c wfas_tp.h | manifest
	@mkdir -p $(B)/tp
	$(CC) $(GLUE_FLAGS) -Ithird_party/libcrux -c $< -o $@

$(B)/wfas.o: wfas.c wfas.h wfas_tp.h
	@mkdir -p $(B)
	$(CC) $(CORE_FLAGS) -c wfas.c -o $@
	$(CLANG) $(CORE_FLAGS) -c wfas.c -o $(B)/wfas.clang.o

# -- sanitizer objects: core, glue and third-party code all instrumented ------

$(B)/san/%.o: $(HACL)/src/%.c | manifest
	@mkdir -p $(B)/san
	$(CC) -std=gnu11 -D_DEFAULT_SOURCE -w $(SAN) $(TP_INC) -c $< -o $@

$(B)/san/wfas_tp.o: wfas_tp.c wfas_tp.h | manifest
	@mkdir -p $(B)/san
	$(CC) -std=gnu11 -w $(SAN) $(TP_INC) -I$(FIAT) -c $< -o $@

$(B)/san/wfas_tp_mlkem.o: wfas_tp_mlkem.c wfas_tp.h | manifest
	@mkdir -p $(B)/san
	$(CC) -std=gnu11 -w $(SAN) -Ithird_party/libcrux -c $< -o $@

$(B)/san_core/wfas.o: wfas.c wfas.h wfas_tp.h
	@mkdir -p $(B)/san_core
	$(CC) -std=c99 -pedantic-errors -Werror -Wall -Wextra $(SAN) -c wfas.c -o $@

# -- test programs ------------------------------------------------------------

$(B)/test_base: tests/test_base.c tests/vectors.h wfas.c wfas.h $(TP_OBJ)
	$(CC) $(CORE_FLAGS) tests/test_base.c $(TP_OBJ) -o $@

$(B)/test_base_clang: tests/test_base.c tests/vectors.h wfas.c wfas.h $(TP_OBJ)
	$(CLANG) $(CORE_FLAGS) tests/test_base.c $(TP_OBJ) -o $@

$(B)/test_base_san: tests/test_base.c tests/vectors.h wfas.c wfas.h $(SAN_TP)
	$(CC) $(TEST_FLAGS) $(SAN) tests/test_base.c $(SAN_TP) -o $@

$(B)/test_hs: tests/test_hs.c wfas.c wfas.h $(TP_OBJ)
	$(CC) $(CORE_FLAGS) tests/test_hs.c $(TP_OBJ) -o $@

$(B)/test_hs_clang: tests/test_hs.c wfas.c wfas.h $(TP_OBJ)
	$(CLANG) $(CORE_FLAGS) tests/test_hs.c $(TP_OBJ) -o $@

$(B)/test_hs_san: tests/test_hs.c wfas.c wfas.h $(SAN_TP)
	$(CC) $(TEST_FLAGS) $(SAN) tests/test_hs.c $(SAN_TP) -o $@

$(B)/test_pair: tests/test_pair.c wfas.c wfas.h $(TP_OBJ)
	$(CC) $(CORE_FLAGS) tests/test_pair.c $(TP_OBJ) -o $@

$(B)/test_pair_clang: tests/test_pair.c wfas.c wfas.h $(TP_OBJ)
	$(CLANG) $(CORE_FLAGS) tests/test_pair.c $(TP_OBJ) -o $@

$(B)/test_pair_san: tests/test_pair.c wfas.c wfas.h $(SAN_TP)
	$(CC) $(TEST_FLAGS) $(SAN) tests/test_pair.c $(SAN_TP) -o $@

$(B)/test_mcast: tests/test_mcast.c wfas.c wfas.h $(TP_OBJ)
	$(CC) $(CORE_FLAGS) tests/test_mcast.c $(TP_OBJ) -o $@

$(B)/test_mcast_clang: tests/test_mcast.c wfas.c wfas.h $(TP_OBJ)
	$(CLANG) $(CORE_FLAGS) tests/test_mcast.c $(TP_OBJ) -o $@

$(B)/test_mcast_san: tests/test_mcast.c wfas.c wfas.h $(SAN_TP)
	$(CC) $(TEST_FLAGS) $(SAN) tests/test_mcast.c $(SAN_TP) -o $@

# The command-line client: POSIX and Linux (getrandom, CLOCK_BOOTTIME).
$(B)/wfascli: wfascli.c wfas.h $(B)/wfas.o $(TP_OBJ)
	$(CC) $(CLI_FLAGS) wfascli.c $(B)/wfas.o $(TP_OBJ) -o $@
	$(CLANG) $(CLI_FLAGS) wfascli.c $(B)/wfas.o $(TP_OBJ) -o $(B)/wfascli_clang

$(B)/interop: tests/interop.c wfas.h $(B)/wfas.o $(TP_OBJ)
	$(CC) -std=c99 -Wall -Wextra -Werror -O2 tests/interop.c $(B)/wfas.o $(TP_OBJ) -o $@

$(B)/interop_san: tests/interop.c wfas.h $(B)/san_core/wfas.o $(SAN_TP)
	$(CC) -std=c99 -Wall -Wextra -Werror $(SAN) tests/interop.c $(B)/san_core/wfas.o $(SAN_TP) -o $@

# -- checks -------------------------------------------------------------------

TESTS := $(B)/test_base $(B)/test_base_clang $(B)/test_base_san \
         $(B)/test_hs $(B)/test_hs_clang $(B)/test_hs_san \
         $(B)/test_pair $(B)/test_pair_clang $(B)/test_pair_san \
         $(B)/test_mcast $(B)/test_mcast_clang $(B)/test_mcast_san $(B)/interop $(B)/interop_san

check: all $(TESTS)
	$(B)/test_base
	$(B)/test_base_clang
	$(B)/test_base_san
	$(B)/test_hs
	$(B)/test_hs_clang
	$(B)/test_hs_san
	$(B)/test_pair
	$(B)/test_pair_clang
	$(B)/test_pair_san
	$(B)/test_mcast
	$(B)/test_mcast_clang
	$(B)/test_mcast_san
	@if command -v valgrind >/dev/null; then \
	  valgrind -q --error-exitcode=9 --leak-check=full $(B)/test_base >/dev/null && \
	  valgrind -q --error-exitcode=9 --leak-check=full $(B)/test_hs >/dev/null && \
	  valgrind -q --error-exitcode=9 --leak-check=full $(B)/test_pair >/dev/null && \
	  valgrind -q --error-exitcode=9 --leak-check=full $(B)/test_mcast >/dev/null && echo "valgrind: clean"; fi
	$(PYTHON) -I tests/interop.py $(B)/interop
	$(PYTHON) -I tests/interop.py $(B)/interop_san
	$(PYTHON) -I tests/interop_pair.py $(B)/interop
	$(PYTHON) -I tests/interop_pair.py $(B)/interop_san
	$(PYTHON) -I tests/interop_mcast.py $(B)/interop
	$(PYTHON) -I tests/interop_mcast.py $(B)/interop_san
	$(PYTHON) -I tests/cli_test.py $(B)/wfascli
	$(PYTHON) -I model/psk_model.py 6
	@$(MAKE) --no-print-directory analyze
	@! LC_ALL=C grep -nP '[^\x00-\x7F]' $(ASCII_FILES) || { echo "non-ASCII byte in a project file"; exit 1; }
	@for f in wfas.c wfas.h; do \
	  sed -e 's#/\*.*\*/##g' $$f | grep -vE '^[[:space:]]*(/\*|\*)' | \
	  grep -nE '\b(malloc|calloc|realloc|free|alloca|goto|setjmp|memcmp|union)\b' && \
	  { echo "forbidden construct in $$f (line numbers after comment removal)"; exit 1; }; \
	done; echo "core constructs: ok"
	@echo "check: all passed"

interop: $(B)/interop
	$(PYTHON) -I tests/interop.py $(B)/interop
	$(PYTHON) -I tests/interop_pair.py $(B)/interop
	$(PYTHON) -I tests/interop_mcast.py $(B)/interop

model:
	$(PYTHON) -I model/psk_model.py 6

mutate: all $(B)/interop
	$(PYTHON) -I tests/mutate.py $(B)/tp $(B)/interop

# Style notes about variable scope are left out: the core declares its
# variables at the top of each function on purpose.
analyze:
	@if command -v cppcheck >/dev/null; then \
	  cppcheck --std=c99 --enable=warning,style,performance,portability -q --error-exitcode=1 \
	    --suppress=missingIncludeSystem --suppress=variableScope -I. wfas.c wfascli.c && echo "cppcheck: clean"; \
	else echo "cppcheck: not installed, skipped"; fi
	@out=$$($(CLANG) --analyze -std=c99 -Xanalyzer -analyzer-output=text -I. wfas.c -o /dev/null 2>&1; \
	  $(CLANG) --analyze -std=c99 -Xanalyzer -analyzer-output=text -I. wfascli.c -o /dev/null 2>&1); \
	  if [ -n "$$out" ]; then echo "$$out"; exit 1; fi; echo "clang analyzer: clean"

vectors:
	$(PYTHON) -I tests/gen_vectors.py tests > tests/vectors.h

clean:
	rm -rf $(B)

.PHONY: all manifest check interop model mutate analyze vectors clean cli-test

cli-test: $(B)/wfascli
	$(PYTHON) -I tests/cli_test.py $(B)/wfascli
