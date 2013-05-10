/**
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
package org.apache.openejb.deployment.entity.cmp.cmr.onetomany;

import javax.ejb.EJBLocalObject;

/**
 *
 * @version $Revision: 710022 $ $Date: 2008-11-03 08:40:14 +0000 (Mon, 03 Nov 2008) $
 */
public interface BLocal extends EJBLocalObject {

    // CMP
    public Integer getField1();
    public void setField1(Integer field1);

    public String getField2();
    public void setField2(String field2);

    public Integer getField3();
    public void setField3(Integer field3);

    public String getField4();
    public void setField4(String field4);

    // CMR
    public ALocal getA();
    public void setA(ALocal a);
}
