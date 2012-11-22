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
package org.apache.xbean.finder.archive;

import org.apache.xbean.finder.filter.Filter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * @version $Rev$ $Date: 2012/05/03 13:43:16 $
 */
public class FilteredArchive implements Archive {

    private final Archive archive;

    private final Filter filter;

    public FilteredArchive(Archive archive, Filter filter) {
        this.archive = archive;
        this.filter = filter;
    }

    public InputStream getBytecode(String className) throws IOException, ClassNotFoundException {
        return archive.getBytecode(className);
    }

    public Class<?> loadClass(String className) throws ClassNotFoundException {
        return archive.loadClass(className);
    }

    public Iterator<String> iterator() {
        return new FilteredIterator(archive.iterator());
    }

    private final class FilteredIterator implements Iterator<String> {
        private final Iterator<String> it;

        private String next;

        private FilteredIterator(Iterator<String> it) {
            this.it = it;
        }

        public boolean hasNext() {
            if (next != null) return true;
            if (!it.hasNext()) return false;
            seek();
            return hasNext();
        }

        public String next() {
            if (!hasNext()) throw new NoSuchElementException();

            String s = next;
            next = null;

            return s;
        }

        public void remove() {
            it.remove();
        }

        private void seek() {
            while (next == null && it.hasNext()) {

                next = it.next();

                if (filter.accept(next)) return;

                next = null;
            }
        }

    }
}