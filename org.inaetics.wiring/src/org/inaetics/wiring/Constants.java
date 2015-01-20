/*
 * Copyright (c) 2010-2013 The Amdatu Foundation
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
package org.inaetics.wiring;

import org.osgi.service.log.LogService;

/**
 *
 * @author <a href="mailto:amdatu-developers@amdatu.org">Amdatu Project Team</a>
 */
public class Constants {

    /**
     * Property key prefix for the log level. Default is {@link LogService#LOG_INFO}.
     */
    public final static String LOGGING_PROP_PRE = "inaetics.wiring.logging";

    /**
     * Property key prefix for the console level. Default is {@link LogService#LOG_ERROR} - 1.
     */
    public final static String CONSOLE_PROP_PRE = "ineatics.wiring.console";

    /**
     * Manifest header key
     */
    public final static String MANIFEST_INEATICS_WIRING_HEADER = "Ineatics-Wiring";

}