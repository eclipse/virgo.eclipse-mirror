With these cmd lines you can build the Virgo-patched openejb-core:
mvn -Dmaven.test.skip=true clean install - skips tests [~1min]
mvn clean install [~17min]

After successful build patch the Bundle-Version and jar name to something like this increasing the current version at the back:
org.apache.openejb.core_4.0.0.beta-2_v201205260545-virgo-2

Add "Exposed-ContentType: Implementation" to the manifest of the newly built openejb-core jar and the package org.apache.openejb.jee.jpa.frag
 ment;version="[4.0,5)" to the Import-Package header or better yet copy the entire manifest from the old hot fix jar into the new one.

 
If you don't copy the whole manifest also remove the version component from the "javax.management.j2ee" package.


Original code is submitted in src.original and can also be found at:
http://svn.apache.org/repos/asf/openejb/tags/openejb-4.0.0-beta-2/
