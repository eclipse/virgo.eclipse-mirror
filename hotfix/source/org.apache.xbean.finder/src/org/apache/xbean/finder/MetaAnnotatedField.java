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
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.xbean.finder;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;

/**
* @version $Rev$ $Date: 2012/05/03 13:43:15 $
*/
public class MetaAnnotatedField extends MetaAnnotatedObject<Field> implements AnnotatedMember<Field> {

    public MetaAnnotatedField(Field field) {
        super(field, unroll(field));
    }

    public Annotation[] getDeclaredAnnotations() {
        return get().getDeclaredAnnotations();
    }

    public Class<?> getDeclaringClass() {
        return get().getDeclaringClass();
    }

    public String getName() {
        return get().getName();
    }

    public int getModifiers() {
        return get().getModifiers();
    }

    public boolean isSynthetic() {
        return get().isSynthetic();
    }

}
