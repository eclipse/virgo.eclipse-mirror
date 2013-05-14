With these cmd lines you can build the Virgo-patched openejb-core:
mvn -Dmaven.test.skip=true clean install - skips tests [~1min]
mvn clean install [~17min]

After successful build patch the Bundle-Version and jar name to something like this increasing the current version at the back:
org.apache.openejb.core_4.5.2.virgo-1

Add "Exposed-ContentType: Implementation" to the manifest of the newly built openejb-core jar.
Add the following packages to the Import-Package header:

org.apache.openejb.api.jmx,
org.apache.openejb.jpa.integration,
org.apache.webbeans.proxy.javassist,
org.apache.webbeans.corespi.security,
org.apache.openejb.jee.jpa.fragment;version="[4.0,5)",
org.apache.commons.lang3,
org.apache.webbeans.web.intercept,
org.slf4j.spi,
javax.servlet.annotation,
org.apache.log4j.spi

Make the imports org.apache.commons.lang and org.apache.commons.lang.math optional: 
org.apache.commons.lang;resolution:=optional;version="[3.1,4)",org.apache.commons.lang.math;resolution:=optional 

or better yet copy the entire manifest from the old hot fix jar into the new one.

Original code is submitted in src.original and can also be found at:
http://svn.apache.org/repos/asf/openejb/tags/openejb-4.5.2/
