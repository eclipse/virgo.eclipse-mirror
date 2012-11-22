/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.config;

/**
 * @version $Rev: 1153797 $ $Date: 2011-08-04 02:09:44 -0700 (Thu, 04 Aug 2011) $
 */
public class UnknownModuleTypeException extends UnsupportedModuleTypeException {
    public UnknownModuleTypeException() {
    }

    public UnknownModuleTypeException(String message) {
        super(message);
    }

    public UnknownModuleTypeException(String message, Throwable rootCause) {
        super(message, rootCause);
    }

    public UnknownModuleTypeException(Throwable rootCause) {
        super(rootCause);
    }
}
