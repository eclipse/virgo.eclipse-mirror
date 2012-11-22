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

import org.apache.openejb.AppContext;
import org.apache.openejb.BeanContext;
import org.apache.openejb.assembler.classic.Assembler;
import org.apache.webbeans.component.InjectionPointBean;
import org.apache.webbeans.component.InjectionTargetWrapper;
import org.apache.webbeans.component.NewBean;
import org.apache.webbeans.component.ProducerFieldBean;
import org.apache.webbeans.component.ProducerMethodBean;
import org.apache.webbeans.component.creation.BeanCreator;
import org.apache.webbeans.config.OWBLogConst;
import org.apache.webbeans.config.OpenWebBeansConfiguration;
import org.apache.webbeans.config.WebBeansContext;
import org.apache.webbeans.config.WebBeansFinder;
import org.apache.webbeans.container.BeanManagerImpl;
import org.apache.webbeans.container.InjectionResolver;
import org.apache.webbeans.ejb.common.component.BaseEjbBean;
import org.apache.webbeans.ejb.common.component.EjbBeanCreatorImpl;
import org.apache.webbeans.ejb.common.util.EjbUtility;
import org.apache.webbeans.event.ObserverMethodImpl;
import org.apache.webbeans.exception.WebBeansConfigurationException;
import org.apache.webbeans.intercept.InterceptorData;
import org.apache.webbeans.logger.WebBeansLogger;
import org.apache.webbeans.portable.AnnotatedElementFactory;
import org.apache.webbeans.portable.events.ExtensionLoader;
import org.apache.webbeans.portable.events.ProcessAnnotatedTypeImpl;
import org.apache.webbeans.portable.events.ProcessInjectionTargetImpl;
import org.apache.webbeans.portable.events.ProcessProducerImpl;
import org.apache.webbeans.portable.events.ProcessSessionBeanImpl;
import org.apache.webbeans.portable.events.discovery.BeforeShutdownImpl;
import org.apache.webbeans.portable.events.generics.GProcessSessionBean;
import org.apache.webbeans.spi.ContainerLifecycle;
import org.apache.webbeans.spi.ContextsService;
import org.apache.webbeans.spi.JNDIService;
import org.apache.webbeans.spi.ResourceInjectionService;
import org.apache.webbeans.spi.ScannerService;
import org.apache.webbeans.spi.adaptor.ELAdaptor;
import org.apache.webbeans.util.WebBeansConstants;
import org.apache.webbeans.util.WebBeansUtil;
import org.apache.webbeans.xml.WebBeansXMLConfigurator;

import javax.el.ELResolver;
import javax.enterprise.inject.Specializes;
import javax.enterprise.inject.spi.AnnotatedField;
import javax.enterprise.inject.spi.AnnotatedMethod;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.ObserverMethod;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.jsp.JspApplicationContext;
import javax.servlet.jsp.JspFactory;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * @version $Rev:$ $Date:$
 */
public class OpenEJBLifecycle implements ContainerLifecycle {

    //Logger instance
    protected static WebBeansLogger logger = WebBeansLogger.getLogger(OpenEJBLifecycle.class);

    /**Discover bean classes*/
    protected ScannerService scannerService;

    protected final ContextsService contextsService;

    /**Deploy discovered beans*/
    private final BeansDeployer deployer;

    /**XML discovery. */
    //XML discovery is removed from the specification. It is here for next revisions of spec.
    private final WebBeansXMLConfigurator xmlDeployer;

    /**Using for lookup operations*/
    private final JNDIService jndiService;

    /**Root container.*/
    private final BeanManagerImpl beanManager;
    private final WebBeansContext webBeansContext;
    /**Manages unused conversations*/
    private ScheduledExecutorService service = null;

    //TODO make sure this isn't used and remove it
    public OpenEJBLifecycle() {
        this(WebBeansContext.currentInstance());
    }

    public OpenEJBLifecycle(WebBeansContext webBeansContext)
    {
        this.webBeansContext = webBeansContext;
        beforeInitApplication(null);

        this.beanManager = webBeansContext.getBeanManagerImpl();
        this.xmlDeployer = new WebBeansXMLConfigurator();
        this.deployer = new BeansDeployer(this.xmlDeployer, webBeansContext);
        this.jndiService = webBeansContext.getService(JNDIService.class);
        this.beanManager.setXMLConfigurator(this.xmlDeployer);
        this.scannerService = webBeansContext.getScannerService();
        this.contextsService = webBeansContext.getContextsService();

        initApplication(null);
    }

    @Override
    public BeanManager getBeanManager()
    {
        return this.beanManager;
    }

    private String readContents(URL resource) throws IOException {
        InputStream in = resource.openStream();
        BufferedInputStream reader = null;
        StringBuffer sb = new StringBuffer();

        try {
            reader = new BufferedInputStream(in);

            int b = reader.read();
            while (b != -1) {
                sb.append((char) b);
                b = reader.read();
            }

            return sb.toString().trim();
        } finally {
            try {
                in.close();
                reader.close();
            } catch (Exception e) {
            }
        }
    }

    @Override
    public void startApplication(Object startupObject)
    {
        if (startupObject instanceof ServletContextEvent) {
            startServletContext((ServletContext) getServletContext(startupObject)); // TODO: check it is relevant
            return;
        } else if (!(startupObject instanceof StartupObject)) {
            logger.debug("startupObject is not of StartupObject type; ignored");
            return;
        }

        StartupObject stuff = (StartupObject) startupObject;
        // Initalize Application Context
        logger.info(OWBLogConst.INFO_0005);

        long begin = System.currentTimeMillis();

        //Before Start
        beforeStartApplication(startupObject);
    	

        //Load all plugins
        webBeansContext.getPluginLoader().startUp();

        //Get Plugin
        CdiPlugin cdiPlugin = (CdiPlugin) webBeansContext.getPluginLoader().getEjbPlugin();

        final AppContext appContext = stuff.getAppContext();

        cdiPlugin.setAppContext(appContext);
        appContext.setWebBeansContext(webBeansContext);
        cdiPlugin.startup();

        //Configure EJB Deployments
        cdiPlugin.configureDeployments(stuff.getBeanContexts());

        //Resournce Injection Service
        CdiResourceInjectionService injectionService = (CdiResourceInjectionService) webBeansContext.getService(ResourceInjectionService.class);
        injectionService.setAppContext(stuff.getAppContext());

        //Deploy the beans
        try {
            //Load Extensions
            loadExtensions(appContext);

            //Initialize contexts
            this.contextsService.init(startupObject);

            //Configure Default Beans
            // need to be done before fireBeforeBeanDiscoveryEvent
            deployer.configureDefaultBeans();

            //Fire Event
            deployer.fireBeforeBeanDiscoveryEvent();

            //Scanning process
            logger.debug("Scanning classpaths for beans artifacts.");

            if (scannerService instanceof CdiScanner) {
                final CdiScanner service = (CdiScanner) scannerService;
                service.init(startupObject);
            } else {
                new CdiScanner().init(startupObject);
            }

            //Scan
            this.scannerService.scan();

            //Deploy bean from XML. Also configures deployments, interceptors, decorators.
            deployer.deployFromXML(scannerService);

            //Checking stereotype conditions
            deployer.checkStereoTypes(scannerService);

            //Discover classpath classes
            deployManagedBeans(scannerService.getBeanClasses(), stuff.getBeanContexts());

            for (BeanContext beanContext : stuff.getBeanContexts()) {
                if (!beanContext.getComponentType().isCdiCompatible() || beanContext.isDynamicallyImplemented()) continue;

                final Class implClass = beanContext.getBeanClass();

                //Define annotation type
                AnnotatedType<?> annotatedType = webBeansContext.getAnnotatedElementFactory().newAnnotatedType(implClass);

                //Fires ProcessAnnotatedType
                ProcessAnnotatedTypeImpl<?> processAnnotatedEvent = webBeansContext.getWebBeansUtil().fireProcessAnnotatedTypeEvent(annotatedType);

                // TODO Can you really veto an EJB?
                //if veto() is called
                if (processAnnotatedEvent.isVeto()) {
                    continue;
                }

                CdiEjbBean<Object> bean = new CdiEjbBean<Object>(beanContext, webBeansContext);

                beanContext.set(CdiEjbBean.class, bean);
                beanContext.set(CurrentCreationalContext.class, new CurrentCreationalContext());
                beanContext.addSystemInterceptor(new CdiInterceptor(bean, beanManager, cdiPlugin.getContexsServices()));

                // should be EjbUtility static method (from OWB 1.1.4)
                fireEvents((Class<Object>) implClass, bean, (ProcessAnnotatedTypeImpl<Object>) processAnnotatedEvent);

                webBeansContext.getWebBeansUtil().setInjectionTargetBeanEnableFlag(bean);

                Class clazz = beanContext.getBeanClass();
                while (clazz.isAnnotationPresent(Specializes.class)) {
                    clazz = clazz.getSuperclass();

                    if (clazz == null || Object.class.equals(clazz)) break;

                    final CdiEjbBean<Object> superBean = new CdiEjbBean<Object>(beanContext, webBeansContext, clazz);

                    EjbBeanCreatorImpl<?> ejbBeanCreator = new EjbBeanCreatorImpl(superBean);

                    //Define meta-data
                    ejbBeanCreator.defineSerializable();
                    ejbBeanCreator.defineStereoTypes();
                    ejbBeanCreator.defineScopeType("Session Bean implementation class : " + clazz.getName() + " stereotypes must declare same @ScopeType annotations", false);
                    ejbBeanCreator.defineQualifier();
                    ejbBeanCreator.defineName(WebBeansUtil.getManagedBeanDefaultName(clazz.getSimpleName()));

                    bean.specialize(superBean);

                    EjbUtility.defineSpecializedData(clazz, bean);
                }
            }

            //Check Specialization
            deployer.checkSpecializations(scannerService);

            //Fire Event
            deployer.fireAfterBeanDiscoveryEvent();

            //Validate injection Points
            deployer.validateInjectionPoints();

            for (BeanContext beanContext : stuff.getBeanContexts()) {
                if (!beanContext.getComponentType().isSession() || beanContext.isDynamicallyImplemented()) continue;
                final CdiEjbBean bean = beanContext.get(CdiEjbBean.class);

                // The interceptor stack is empty until validateInjectionPoints is called as it does more than validate.
                final List<InterceptorData> datas = bean.getInterceptorStack();

                final List<org.apache.openejb.core.interceptor.InterceptorData> converted = new ArrayList<org.apache.openejb.core.interceptor.InterceptorData>();
                for (InterceptorData data : datas) {
                    // todo this needs to use the code in InterceptorBindingBuilder that respects override rules and private methods
                    converted.add(org.apache.openejb.core.interceptor.InterceptorData.scan(data.getInterceptorClass()));
                }

                beanContext.setCdiInterceptors(converted);
            }

            //Fire Event
            deployer.fireAfterDeploymentValidationEvent();

            for (BeanContext beanContext : stuff.getBeanContexts()) {

                final CdiEjbBean<Object> bean = beanContext.get(CdiEjbBean.class);;

                if (bean == null) continue;

                final BeanManagerImpl manager = webBeansContext.getBeanManagerImpl();
                manager.addBean(new NewCdiEjbBean<Object>(bean));
            }

        } catch (Exception e1) {
            Assembler.logger.error("CDI Beans module deployment failed", e1);
            throw new RuntimeException(e1);
        }
        //Start actual starting on sub-classes
        afterStartApplication(startupObject);

        logger.info(OWBLogConst.INFO_0001, Long.toString(System.currentTimeMillis() - begin));
    }

    public static <T> void fireEvents(Class<T> clazz, BaseEjbBean<T> ejbBean,ProcessAnnotatedType<T> event)
    {
        WebBeansContext webBeansContext = ejbBean.getWebBeansContext();
        BeanManagerImpl manager = webBeansContext.getBeanManagerImpl();
        AnnotatedElementFactory annotatedElementFactory = webBeansContext.getAnnotatedElementFactory();

        AnnotatedType<T> annotatedType = annotatedElementFactory.newAnnotatedType(clazz);

        //Fires ProcessAnnotatedType
        ProcessAnnotatedTypeImpl<T> processAnnotatedEvent = (ProcessAnnotatedTypeImpl<T>)event;
        EjbBeanCreatorImpl<T> ejbBeanCreator = new EjbBeanCreatorImpl<T>(ejbBean);
        ejbBeanCreator.checkCreateConditions();

        if(processAnnotatedEvent.isVeto())
        {
            return;
        }

        if(processAnnotatedEvent.isModifiedAnnotatedType())
        {
            ejbBeanCreator.setMetaDataProvider(BeanCreator.MetaDataProvider.THIRDPARTY);
            ejbBeanCreator.setAnnotatedType(annotatedType);
        }

        //Define meta-data
        ejbBeanCreator.defineSerializable();
        ejbBeanCreator.defineStereoTypes();
        ejbBeanCreator.defineApiType();
        ejbBeanCreator.defineScopeType("Session Bean implementation class : " + clazz.getName() + " stereotypes must declare same @ScopeType annotations", false);
        ejbBeanCreator.defineQualifier();
        ejbBeanCreator.defineName(WebBeansUtil.getManagedBeanDefaultName(clazz.getSimpleName()));
        Set<ProducerMethodBean<?>> producerMethodBeans = ejbBeanCreator.defineProducerMethods();
        for(ProducerMethodBean<?> producerMethodBean : producerMethodBeans)
        {
            Method producerMethod = producerMethodBean.getCreatorMethod();
            if(!Modifier.isStatic(producerMethod.getModifiers()))
            {
                if(!EjbUtility.isBusinessMethod(producerMethod, ejbBean))
                {
                    throw new WebBeansConfigurationException("Producer Method Bean must be business method of session bean : " + ejbBean);
                }
            }
        }
        Set<ProducerFieldBean<?>> producerFieldBeans = ejbBeanCreator.defineProducerFields();
        ejbBeanCreator.defineInjectedFields();
        ejbBeanCreator.defineInjectedMethods();
        Set<ObserverMethod<?>> observerMethods = ejbBeanCreator.defineObserverMethods();

        //Fires ProcessInjectionTarget
        ProcessInjectionTargetImpl<T> processInjectionTargetEvent =
                webBeansContext.getWebBeansUtil().fireProcessInjectionTargetEvent(ejbBean);
        webBeansContext.getWebBeansUtil().inspectErrorStack(
                "There are errors that are added by ProcessInjectionTarget event observers. Look at logs for further details");
        //Put final InjectionTarget instance
        manager.putInjectionTargetWrapper(ejbBean, new InjectionTargetWrapper(processInjectionTargetEvent.getInjectionTarget()));

        Map<ProducerMethodBean<?>,AnnotatedMethod<?>> annotatedMethods = new HashMap<ProducerMethodBean<?>, AnnotatedMethod<?>>();
        for(ProducerMethodBean<?> producerMethod : producerMethodBeans)
        {
            AnnotatedMethod<?> method = annotatedElementFactory.newAnnotatedMethod(producerMethod.getCreatorMethod(), annotatedType);
            ProcessProducerImpl<?, ?> producerEvent =
                    webBeansContext.getWebBeansUtil().fireProcessProducerEventForMethod(producerMethod,
                            method);
            webBeansContext.getWebBeansUtil().inspectErrorStack(
                    "There are errors that are added by ProcessProducer event observers for ProducerMethods. Look at logs for further details");

            annotatedMethods.put(producerMethod, method);
            manager.putInjectionTargetWrapper(producerMethod, new InjectionTargetWrapper(producerEvent.getProducer()));

            producerEvent.setProducerSet(false);
        }

        Map<ProducerFieldBean<?>,AnnotatedField<?>> annotatedFields = new HashMap<ProducerFieldBean<?>, AnnotatedField<?>>();
        for(ProducerFieldBean<?> producerField : producerFieldBeans)
        {
            AnnotatedField<?> field = annotatedElementFactory.newAnnotatedField(producerField.getCreatorField(), annotatedType);
            ProcessProducerImpl<?, ?> producerEvent =
                    webBeansContext.getWebBeansUtil().fireProcessProducerEventForField(producerField, field);
            webBeansContext.getWebBeansUtil().inspectErrorStack(
                    "There are errors that are added by ProcessProducer event observers for ProducerFields. Look at logs for further details");

            annotatedFields.put(producerField, field);
            manager.putInjectionTargetWrapper(producerField, new InjectionTargetWrapper(producerEvent.getProducer()));


            producerEvent.setProducerSet(false);
        }

        Map<ObserverMethod<?>,AnnotatedMethod<?>> observerMethodsMap = new HashMap<ObserverMethod<?>, AnnotatedMethod<?>>();
        for(ObserverMethod<?> observerMethod : observerMethods)
        {
            ObserverMethodImpl<?> impl = (ObserverMethodImpl<?>)observerMethod;
            AnnotatedMethod<?> method = annotatedElementFactory.newAnnotatedMethod(impl.getObserverMethod(), annotatedType);

            observerMethodsMap.put(observerMethod, method);
        }

        //Fires ProcessManagedBean
        ProcessSessionBeanImpl<T> processBeanEvent = new GProcessSessionBean((Bean<Object>)ejbBean,annotatedType,ejbBean.getEjbName(),ejbBean.getEjbType());
        webBeansContext.getBeanManagerImpl().fireEvent(processBeanEvent, new Annotation[0]);
        webBeansContext.getWebBeansUtil().inspectErrorStack(
                "There are errors that are added by ProcessSessionBean event observers for managed beans. Look at logs for further details");

        //Fires ProcessProducerMethod
        webBeansContext.getWebBeansUtil().fireProcessProducerMethodBeanEvent(annotatedMethods,
                annotatedType);
        webBeansContext.getWebBeansUtil().inspectErrorStack(
                "There are errors that are added by ProcessProducerMethod event observers for producer method beans. Look at logs for further details");

        //Fires ProcessProducerField
        webBeansContext.getWebBeansUtil().fireProcessProducerFieldBeanEvent(annotatedFields);
        webBeansContext.getWebBeansUtil().inspectErrorStack(
                "There are errors that are added by ProcessProducerField event observers for producer field beans. Look at logs for further details");

        //Fire ObservableMethods
        webBeansContext.getWebBeansUtil().fireProcessObservableMethodBeanEvent(observerMethodsMap);
        webBeansContext.getWebBeansUtil().inspectErrorStack(
                "There are errors that are added by ProcessObserverMethod event observers for observer methods. Look at logs for further details");

        manager.addBean(ejbBean);

        // Let the plugin handle adding the new bean instance as it knows more about its EJB Bean

        manager.getBeans().addAll(producerMethodBeans);
        ejbBeanCreator.defineDisposalMethods();
        manager.getBeans().addAll(producerFieldBeans);
    }

    public static class NewEjbBean<T> extends CdiEjbBean<T> implements NewBean<T> {

        public NewEjbBean(BeanContext beanContext, WebBeansContext webBeansContext) {
            super(beanContext, webBeansContext);
        }


    }

    private void loadExtensions(AppContext appContext) throws IOException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        final ExtensionLoader extensionLoader = webBeansContext.getExtensionLoader();

        // Load regularly visible Extensions
        extensionLoader.loadExtensionServices(appContext.getClassLoader());

        // Load any potentially misplaced extensions -- TCK seems to be full of them
        // This could perhaps be improved or addressed elsewhere
//        final String s = "WEB-INF/classes/META-INF/services/javax.enterprise.inject.spi.Extension";
//        final ArrayList<URL> list = Collections.list(appContext.getClassLoader().getResources(s));
//        for (URL url : list) {
//            final String className = readContents(url).trim();
//
//            final Class<?> extensionClass = appContext.getClassLoader().loadClass(className);
//
//            if (Extension.class.isAssignableFrom(extensionClass)) {
//                final Extension extension = (Extension) extensionClass.newInstance();
//                extensionLoader.addExtension(extension);
//            }
//        }
    }

    private void deployManagedBeans(Set<Class<?>> beanClasses, List<BeanContext> ejbs) {
        Set<Class<?>> managedBeans = new HashSet<Class<?>>(beanClasses);
        for (BeanContext beanContext: ejbs) {
            if (beanContext.getComponentType().isSession()) {
                managedBeans.remove(beanContext.getBeanClass());
            }
        }
        // Start from the class
        for (Class<?> implClass : managedBeans) {
            //Define annotation type
            AnnotatedType<?> annotatedType = webBeansContext.getAnnotatedElementFactory().newAnnotatedType(implClass);

            //Fires ProcessAnnotatedType
            ProcessAnnotatedTypeImpl<?> processAnnotatedEvent = webBeansContext.getWebBeansUtil().fireProcessAnnotatedTypeEvent(annotatedType);

            //if veto() is called
            if (processAnnotatedEvent.isVeto()) {
                continue;
            }

            deployer.defineManagedBean((Class<Object>) implClass, (ProcessAnnotatedTypeImpl<Object>) processAnnotatedEvent);
        }
    }

    @Override
    public void stopApplication(Object endObject)
    {
        logger.debug("OpenWebBeans Container is stopping.");

        try
        {
            //Sub-classes operations
            beforeStopApplication(endObject);

            //Fire shut down
            this.beanManager.fireEvent(new BeforeShutdownImpl(), new Annotation[0]);

            //Destroys context
            this.contextsService.destroy(endObject);

            //Unbind BeanManager
            jndiService.unbind(WebBeansConstants.WEB_BEANS_MANAGER_JNDI_NAME);

            //Free all plugin resources
            webBeansContext.getPluginLoader().shutDown();

            //Clear extensions
            webBeansContext.getExtensionLoader().clear();

            //Delete Resolutions Cache
            InjectionResolver.getInstance().clearCaches();

            //Delte proxies
            webBeansContext.getJavassistProxyFactory().clear();

            //Delete AnnotateTypeCache
            webBeansContext.getAnnotatedElementFactory().clear();

            //Delete JMS Model Cache
            webBeansContext.getjMSManager().clear();

            //After Stop
            afterStopApplication(endObject);

            // Clear BeanManager
            this.beanManager.clear();

            // Clear singleton list
            WebBeansFinder.clearInstances(WebBeansUtil.getCurrentClassLoader());

        }
        catch (Exception e)
        {
            logger.error(OWBLogConst.ERROR_0021, e);
        }

    }

    /**
     * @return the logger
     */
    protected WebBeansLogger getLogger()
    {
        return logger;
    }

    /**
     * @return the scannerService
     */
    protected ScannerService getScannerService()
    {
        return scannerService;
    }

    /**
     * @return the contextsService
     */
    public ContextsService getContextService()
    {
        return contextsService;
    }

    /**
     * @return the deployer
     */
    protected BeansDeployer getDeployer()
    {
        return deployer;
    }

    /**
     * @return the xmlDeployer
     */
    protected WebBeansXMLConfigurator getXmlDeployer()
    {
        return xmlDeployer;
    }

    /**
     * @return the jndiService
     */
    protected JNDIService getJndiService()
    {
        return jndiService;
    }

    @Override
    public void initApplication(Properties properties)
    {
        afterInitApplication(properties);
    }

    protected void beforeInitApplication(Properties properties)
    {
        //Do nothing as default
    }

    protected void afterInitApplication(Properties properties)
    {
        //Do nothing as default
    }

    protected void afterStartApplication(final Object startupObject)
    {
    }

    public void startServletContext(final ServletContext servletContext) {
        service = initializeServletContext(servletContext, webBeansContext);
    }

    public static ScheduledExecutorService initializeServletContext(final ServletContext servletContext, WebBeansContext context) {
        String strDelay = context.getOpenWebBeansConfiguration().getProperty(OpenWebBeansConfiguration.CONVERSATION_PERIODIC_DELAY, "150000");
        long delay = Long.parseLong(strDelay);

        final ScheduledExecutorService executorService = Executors.newScheduledThreadPool(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runable) {
                Thread t = new Thread(runable, "OwbConversationCleaner-" + servletContext.getContextPath());
                t.setDaemon(true);
                return t;
            }
        });
        executorService.scheduleWithFixedDelay(new ConversationCleaner(context), delay, delay, TimeUnit.MILLISECONDS);

        ELAdaptor elAdaptor = context.getService(ELAdaptor.class);
        ELResolver resolver = elAdaptor.getOwbELResolver();
        //Application is configured as JSP
        if (context.getOpenWebBeansConfiguration().isJspApplication()) {
            logger.debug("Application is configured as JSP. Adding EL Resolver.");

            JspFactory factory = JspFactory.getDefaultFactory();
            if (factory != null) {
                JspApplicationContext applicationCtx = factory.getJspApplicationContext(servletContext);
                applicationCtx.addELResolver(resolver);
            } else {
                logger.debug("Default JspFactory instance was not found");
            }
        }

        // Add BeanManager to the 'javax.enterprise.inject.spi.BeanManager' servlet context attribute
        servletContext.setAttribute(BeanManager.class.getName(), context.getBeanManagerImpl());

        return executorService;
    }

    /**
     * Conversation cleaner thread, that
     * clears unused conversations.
     *
     */
    private static class ConversationCleaner implements Runnable
    {
        private final WebBeansContext webBeansContext;

        private ConversationCleaner(WebBeansContext webBeansContext) {
            this.webBeansContext = webBeansContext;
        }

        public void run()
        {
            webBeansContext.getConversationManager().destroyWithRespectToTimout();

        }
    }

    protected void afterStopApplication(Object stopObject) throws Exception
    {

        //Clear the resource injection service
        ResourceInjectionService injectionServices = webBeansContext.getService(ResourceInjectionService.class);
        if(injectionServices != null)
        {
            injectionServices.clear();
        }

        //Comment out for commit OWB-502
        //ContextFactory.cleanUpContextFactory();

        this.cleanupShutdownThreadLocals();

        if (logger.wblWillLogInfo())
        {
            stopObject = getServletContext(stopObject);
            logger.info(OWBLogConst.INFO_0002, stopObject instanceof ServletContext? ((ServletContext)stopObject).getContextPath() : stopObject);
        }
    }

    /**
     * Ensures that all ThreadLocals, which could have been set in this
     * (shutdown-) Thread, are removed in order to prevent memory leaks.
     */
    private void cleanupShutdownThreadLocals()
    {
        // TODO maybe there are more to cleanup

        InjectionPointBean.removeThreadLocal();
    }

    /**
     * Returns servelt context otherwise throws exception.
     * @param object object
     * @return servlet context
     */
    private Object getServletContext(Object object) {
        if (object instanceof ServletContextEvent) {
            object = ((ServletContextEvent) object).getServletContext();
            return object;
        }
        return object;
    }

    protected void beforeStartApplication(Object startupObject)
    {
        //Do nothing as default
    }

    protected void beforeStopApplication(Object stopObject) throws Exception
    {
        if(service != null)
        {
            service.shutdownNow();
        }
    }
}
