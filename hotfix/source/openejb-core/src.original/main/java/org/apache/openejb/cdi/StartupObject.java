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


package org.apache.openejb.cdi;

import java.util.List;

import org.apache.openejb.AppContext;
import org.apache.openejb.BeanContext;
import org.apache.openejb.assembler.classic.AppInfo;

/**
 * @version $Rev:$ $Date:$
 */
public class StartupObject {
    private final AppInfo appInfo;
    private final AppContext appContext;
    private final List<BeanContext> beanContexts;

    public StartupObject(AppContext appContext, AppInfo appInfo, List<BeanContext> beanContexts) {
        this.appContext = appContext;
        this.appInfo = appInfo;
        this.beanContexts = beanContexts;
    }

    public AppContext getAppContext() {
        return appContext;
    }

    public AppInfo getAppInfo() {
        return appInfo;
    }

    public List<BeanContext> getBeanContexts() {
        return beanContexts;
    }
}
