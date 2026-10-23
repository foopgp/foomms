// Reduced from thunderbird-android,
// plugins/openpgp-api-lib/openpgp-api/src/main/java/org/openintents/openpgp/util/OpenPgpUtils.java,
// read on 2026-09-22. Apache-2.0, as its header says.
//
// NOT unchanged, and that is the point: the file as published also carries the
// client's half -- finding a provider, parsing a message, splitting a user id
// -- which needs OpenPgpApi and which we are the other side of. What is kept
// is the pair of helpers the result objects use in their toString, copied as
// they are. The rest is theirs to run.
/*
 * Copyright (C) 2015 Dominik Schürmann <dominik@dominikschuermann.de>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.openintents.openpgp.util;

public class OpenPgpUtils {

    public static String convertKeyIdToHex(long keyId) {
        return "0x" + convertKeyIdToHex32bit(keyId >> 32) + convertKeyIdToHex32bit(keyId);
    }

    private static String convertKeyIdToHex32bit(long keyId) {
        String hexString = Long.toHexString(keyId & 0xffffffffL).toLowerCase(java.util.Locale.ENGLISH);
        while (hexString.length() < 8) {
            hexString = "0" + hexString;
        }
        return hexString;
    }
}
