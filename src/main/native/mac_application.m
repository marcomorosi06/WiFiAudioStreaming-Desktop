/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
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
 */

#import <AppKit/AppKit.h>
#import <dispatch/dispatch.h>
#include <jni.h>

/* Never synchronously wait from AWT: Cocoa may be waiting for AWT itself. */
JNIEXPORT void JNICALL
Java_MacApplication_nativeSetWindowVisible(JNIEnv *env, jobject self, jboolean visible) {
    (void)env;
    (void)self;
    dispatch_async(dispatch_get_main_queue(), ^{
        NSApplication *app = NSApp;
        if (app == nil) return;
        NSApplicationActivationPolicy policy = visible
            ? NSApplicationActivationPolicyRegular
            : NSApplicationActivationPolicyAccessory;
        if ([app activationPolicy] == policy) return;
        if (![app setActivationPolicy:policy]) {
            NSLog(@"[WFAS] Failed to change Dock activation policy");
            return;
        }
        if (visible) [app activateIgnoringOtherApps:YES];
    });
}
