/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 * wfas3_glue.h - shared by the two glue files (wfas3_jni.c, wfas3_opus.c).
 */
#ifndef WFAS3_GLUE_H
#define WFAS3_GLUE_H

#include <jni.h>
#include <stddef.h>
#include <stdint.h>

void     w3_wipe(void *p, size_t n);
uint64_t w3_clock_us(void);
int      w3_random(uint8_t *p, size_t n);

/* Binds the Opus natives to WFAS3_OPUS_CLASS. 0 on success. */
int w3_opus_register(JNIEnv *env);

#endif
