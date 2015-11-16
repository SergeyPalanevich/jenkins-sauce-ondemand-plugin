/*
 * The MIT License
 *
 * Copyright (c) 2010, InfraDNA, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.sauce_ondemand;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.google.common.base.Strings;
import com.saucelabs.ci.Browser;
import com.saucelabs.ci.sauceconnect.AbstractSauceTunnelManager;
import com.saucelabs.hudson.HudsonSauceConnectFourManager;
import com.saucelabs.hudson.HudsonSauceManagerFactory;
import hudson.*;
import hudson.console.LineTransformationOutputStream;
import hudson.model.*;
import hudson.model.listeners.ItemListener;
import hudson.plugins.sauce_ondemand.credentials.impl.SauceCredentialsImpl;
import hudson.plugins.sauce_ondemand.credentials.impl.SauceCredentialsListBoxModel;
import hudson.remoting.Callable;
import hudson.security.ACL;
import hudson.tasks.BuildWrapper;
import hudson.util.ListBoxModel;
import hudson.util.VariableResolver;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.jenkins_ci.plugins.run_condition.RunCondition;
import org.json.JSONException;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link BuildWrapper} that sets up the Sauce OnDemand SSH tunnel and populates environment variables which
 * represent the selected browser(s).
 *
 * @author Kohsuke Kawaguchi
 * @author Ross Rowe
 */
public class SauceOnDemandBuildWrapper extends BuildWrapper implements Serializable {

    /**
     * Logger instance.
     */
    private static final Logger logger = Logger.getLogger(SauceOnDemandBuildWrapper.class.getName());

    /**
     * Environment variable key which contains Selenium Client Factory driver for selected browser.
     */
    public static final String SELENIUM_DRIVER = "SELENIUM_DRIVER";
    /**
     * Environment variable key which contains a JSON formatted list of selected browsers.
     */
    public static final String SAUCE_ONDEMAND_BROWSERS = "SAUCE_ONDEMAND_BROWSERS";
    /**
     * Environment variable key which contains the selenium host.
     */
    public static final String SELENIUM_HOST = "SELENIUM_HOST";
    /**
     * Environment variable key which contains the selenium port.
     */
    public static final String SELENIUM_PORT = "SELENIUM_PORT";
    /**
     * Environment variable key which contains the Sauce user name.
     */
    private static final String SAUCE_USERNAME = "SAUCE_USERNAME";
    /**
     * Environment variable key which contains the Sauce user name.
     * @deprecated  As of release 1.142, please use the standard SAUCE_USERNAME instead
     */
    @Deprecated
    private static final String SAUCE_USER_NAME = "SAUCE_USER_NAME";
    /**
     * Environment variable key which contains the Sauce access key.
     * @deprecated  As of release 1.142, please use the standard SAUCE_USERNAME instead
     */
    @Deprecated
    private static final String SAUCE_API_KEY = "SAUCE_API_KEY";
    /**
     * Environment variable key which contains the Sauce access key.
     */
    private static final String SAUCE_ACCESS_KEY = "SAUCE_ACCESS_KEY";
    /**
     * Environment variable key which contains the device value for the selected browser.
     */
    public static final String SELENIUM_DEVICE = "SELENIUM_DEVICE";
    /**
     * Environment variable key which contains the device type for the selected browser.
     */
    public static final String SELENIUM_DEVICE_TYPE = "SELENIUM_DEVICE_TYPE";
    /**
     * Environment variable key which contains the device orientation for the selected browser.
     */
    public static final String SELENIUM_DEVICE_ORIENTATION = "SELENIUM_DEVICE_ORIENTATION";
    /**
     * Regex pattern which is used to identify replacement parameters.
     */
    public static final Pattern ENVIRONMENT_VARIABLE_PATTERN = Pattern.compile("[$|%][{]?([a-zA-Z_][a-zA-Z0-9_]+)[}]?");
    /**
     * Environment variable key which contains the browser value for the selected browser.
     */
    public static final String SELENIUM_BROWSER = "SELENIUM_BROWSER";
    /**
     * Environment variable key which contains the platform for the selected browser.
     */
    public static final String SELENIUM_PLATFORM = "SELENIUM_PLATFORM";
    /**
     * Environment variable key which contains the version for the selected browser.
     */
    public static final String SELENIUM_VERSION = "SELENIUM_VERSION";
    /**
     * Environment variable key which contains the Jenkins build number.
     */
    private static final String JENKINS_BUILD_NUMBER = "JENKINS_BUILD_NUMBER";
    private static final String TUNNEL_IDENTIFIER = "TUNNEL_IDENTIFIER";

    /**
     * Environment variable key which contains the native app path.
     */
    private static final String SAUCE_NATIVE_APP = "SAUCE_NATIVE_APP";

    /**
     * Environment variable key which specifies whether Chrome should be used for Android devices.
     */
    private static final String SAUCE_USE_CHROME = "SAUCE_USE_CHROME";

    private boolean useGeneratedTunnelIdentifier;

    private static final long serialVersionUID = 1L;
    /**
     * Indicates whether the plugin should send usage data to Sauce Labs.
     * @deprecated moved to global scope
     * @see PluginImpl
     */
    @SuppressWarnings("unused")
    @Deprecated
    transient private boolean sendUsageData;
    /**
     * The path to the native app package to be tested.
     */
    private String nativeAppPackage;
    /**
     * Indicates whether Chrome should be used for Android devices.
     */
    private boolean useChromeForAndroid;

    /**
     * The path to an existing Sauce Connect binary.
     */
    private String sauceConnectPath;
    /**
     * Indicates whether Sauce Connect should be started as part of the build.
     */
    private boolean enableSauceConnect;
    /**
     * Map of log parser instances, keyed on the Jenkins build number.
     */
    private Map<String, SauceOnDemandLogParser> logParserMap;
    /**
     * Host location of the selenium server.
     */
    private String seleniumHost;
    /**
     * Port location of the selenium server.
     */
    private String seleniumPort;
    /**
     * The Sauce username/access key that should be used for the build.
     * @deprecated use credentialsId instead
     */
    @Deprecated
    private Credentials credentials;
    /**
     * The browser information that is to be used for the build.
     */
    private SeleniumInformation seleniumInformation;
    /**
     * The list of selected WebDriver browsers.
     */
    private List<String> webDriverBrowsers;
    /**
     * The list of selected Appium browsers.
     */
    private List<String> appiumBrowsers;
    /**
     * Boolean which indicates whether the latest available version of the browser should be used.
     */
    private boolean useLatestVersion;
    /**
     * Default behaviour is to launch Sauce Connect on the slave node.
     */
    private boolean launchSauceConnectOnSlave = true;
    /**
     * The Sauce Connect command line options to be used.
     */
    private String options;
    /**
     * Default verbose logging to true.
     */
    private boolean verboseLogging = true;
    /**
     * RunCondition which allows users to define rules which enable Sauce Connect.
     */
    private RunCondition condition;
    /**
     * @see CredentialsProvider
     */
    private String credentialId;


    /**
     * Constructs a new instance using data entered on the job configuration screen.
     *  @param enableSauceConnect        indicates whether Sauce Connect should be started as part of the build.
     * @param condition                 allows users to define rules which enable Sauce Connect
     * @param credentials               the Sauce username/access key that should be used for the build.
     * @param seleniumInformation       the browser information that is to be used for the build.
     * @param seleniumHost              host location of the selenium server.
     * @param seleniumPort              port location of the selenium server.
     * @param options                   the Sauce Connect command line options to be used
     * @param sauceConnectPath          Path to sauce connect
     * @param launchSauceConnectOnSlave indicates whether Sauce Connect should be launched on the slave or master node
     * @param verboseLogging            indicates whether the Sauce Connect output should be written to the Jenkins job output
     * @param useLatestVersion          indicates whether the latest version of the selected browser(s) should be used
     * @param webDriverBrowsers         which browser(s) should be used for web driver
     * @param appiumBrowsers            which browser(s( should be used for appium
     * @param nativeAppPackage          indicates whether the latest version of the selected browser(s) should be used
     * @param useGeneratedTunnelIdentifier indicated whether tunnel identifers and ports should be managed by the plugin
     * @param credentialId              Which credential a build should use
     */
    @DataBoundConstructor
    public SauceOnDemandBuildWrapper(
        boolean enableSauceConnect,
        RunCondition condition,
        Credentials credentials,
        SeleniumInformation seleniumInformation,
        String seleniumHost,
        String seleniumPort,
        String options,
        String sauceConnectPath,
        boolean launchSauceConnectOnSlave,
        boolean verboseLogging,
        boolean useLatestVersion,
        List<String> webDriverBrowsers,
        List<String> appiumBrowsers,
        String nativeAppPackage,
//            boolean useChromeForAndroid,
        boolean useGeneratedTunnelIdentifier,
        String credentialId) {
        this.credentials = credentials;
        this.seleniumInformation = seleniumInformation;
        this.enableSauceConnect = enableSauceConnect;
        this.seleniumHost = seleniumHost;
        this.seleniumPort = seleniumPort;
        this.options = options;
        this.webDriverBrowsers = webDriverBrowsers;
        this.appiumBrowsers = appiumBrowsers;
        if (seleniumInformation != null) {
            this.webDriverBrowsers = seleniumInformation.getWebDriverBrowsers();
            this.appiumBrowsers = seleniumInformation.getAppiumBrowsers();
        }
        this.launchSauceConnectOnSlave = launchSauceConnectOnSlave;
        this.verboseLogging = verboseLogging;
        this.useLatestVersion = useLatestVersion;
        this.condition = condition;
        this.sauceConnectPath = sauceConnectPath;
        this.nativeAppPackage = nativeAppPackage;
//        this.useChromeForAndroid = useChromeForAndroid;
        this.useGeneratedTunnelIdentifier = useGeneratedTunnelIdentifier;
        this.credentialId = credentialId;
    }


    /**
     * {@inheritDoc}
     *
     * Invoked prior to the running of a Jenkins build.  Populates the Sauce specific environment variables and launches Sauce Connect.
     *
     * @return a new {@link hudson.model.Environment} instance populated with the Sauce environment variables
     */
    @Override
    public Environment setUp(final AbstractBuild build, Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        listener.getLogger().println("Starting pre-build for Sauce Labs plugin");
        logger.fine("Setting up Sauce Build Wrapper");

        final String tunnelIdentifier = SauceEnvironmentUtil.generateTunnelIdentifier(build.getProject().getName());
        final SauceConnectHandler sauceConnectStarter;
        if (isEnableSauceConnect()) {

            boolean canRun = true;
            String workingDirectory = PluginImpl.get().getSauceConnectDirectory();
            String resolvedOptions = getCommandLineOptions(build, listener);

            if (isUseGeneratedTunnelIdentifier()) {
                build.getBuildVariables().put(TUNNEL_IDENTIFIER, tunnelIdentifier);
                resolvedOptions = "--tunnel-identifier " + tunnelIdentifier + " " + resolvedOptions;
            }

            try {
                if (condition != null) {
                    canRun = condition.runPerform(build, listener);
                }
            } catch (Exception e) {
                listener.getLogger().println("Error checking Sauce Connect run condition");
                throw new IOException(e);
            }

            EnvVars env = new EnvVars();
            try {
                env = build.getEnvironment(listener);
            } catch (IOException e) {
                listener.getLogger().println("Error getting environment variables");
                throw e;
            } catch (InterruptedException e) {
                listener.getLogger().println("Error getting environment variables");
                throw e;
            }

            if (canRun) {
                sauceConnectStarter = new SauceConnectHandler(this, env, listener, workingDirectory, resolvedOptions);

                if (launchSauceConnectOnSlave) {
                    listener.getLogger().println("Starting Sauce Connect on slave node using tunnel identifier: " + tunnelIdentifier);
                    Computer.currentComputer().getChannel().call(sauceConnectStarter);

                } else {
                    listener.getLogger().println("Starting Sauce Connect on master node using identifier: " + AbstractSauceTunnelManager.getTunnelIdentifier(resolvedOptions, "default"));
                    //launch Sauce Connect on the master
                    sauceConnectStarter.call();

                }
            } else {
                listener.getLogger().println("Sauce Connect launch skipped due to run condition");
                sauceConnectStarter = null;
            }
        } else {
            sauceConnectStarter = null;
        }
        listener.getLogger().println("Finished pre-build for Sauce Labs plugin");

        if (PluginImpl.get().isSendUsageData()) {
            JenkinsSauceREST sauceREST = new JenkinsSauceREST(getUserName(), getApiKey());
            try {
                logger.fine("Reporting usage data");
                sauceREST.recordCI("jenkins", Jenkins.VERSION.toString());
            } catch (Exception e) {
                logger.finest("Error reporting in: " + e.getMessage());
                // This is purely for informational purposes, so if it fails, just keep going
            }
        }

        return new Environment() {

            /**
             * Updates the environment variable map to include the Sauce specific environment variables applicable to the build.
             * @param env existing environment variables
             */
            @Override
            public void buildEnvVars(Map<String, String> env) {
                logger.fine("Creating Sauce environment variables");
                if (verboseLogging)
                {
                    listener.getLogger().println("The Sauce plugin has set the following environment variables:");
                }
                List<Browser> browsers = new ArrayList<Browser>();
                if (webDriverBrowsers != null) {
                    for (String webDriverBrowser : webDriverBrowsers) {
                        browsers.add(PluginImpl.BROWSER_FACTORY.webDriverBrowserForKey(webDriverBrowser, useLatestVersion));
                    }
                }
                if (appiumBrowsers != null) {
                    for (String appiumBrowser : appiumBrowsers) {
                        browsers.add(PluginImpl.BROWSER_FACTORY.appiumBrowserForKey(appiumBrowser));
                    }
                }
                SauceEnvironmentUtil.outputVariables(env, browsers, getUserName(), getApiKey(), verboseLogging, listener.getLogger());
                //if any variables have been defined in build variables (ie. by a multi-config project), use them
                Map buildVariables = build.getBuildVariables();
                if (buildVariables.containsKey(SELENIUM_BROWSER)) {
                    SauceEnvironmentUtil.outputEnvironmentVariable(env, SELENIUM_BROWSER, (String) buildVariables.get(SELENIUM_BROWSER), true, verboseLogging, listener.getLogger());
                }
                if (buildVariables.containsKey(SELENIUM_VERSION)) {
                    SauceEnvironmentUtil.outputEnvironmentVariable(env, SELENIUM_VERSION, (String) buildVariables.get(SELENIUM_VERSION), true, verboseLogging, listener.getLogger());
                }
                if (buildVariables.containsKey(SELENIUM_PLATFORM)) {
                    SauceEnvironmentUtil.outputEnvironmentVariable(env, SELENIUM_PLATFORM, (String) buildVariables.get(SELENIUM_PLATFORM), true, verboseLogging, listener.getLogger());
                }
                SauceEnvironmentUtil.outputEnvironmentVariable(env, JENKINS_BUILD_NUMBER, sanitiseBuildNumber(build.toString()), true, verboseLogging, listener.getLogger());
                /* Legacy Env name */
                SauceEnvironmentUtil.outputEnvironmentVariable(env, SAUCE_USER_NAME, getUserName(), true, verboseLogging, listener.getLogger());
                /* New standard env name */
                SauceEnvironmentUtil.outputEnvironmentVariable(env, SAUCE_USERNAME, getUserName(), true, verboseLogging, listener.getLogger());

                /* Legacy Env name */
                SauceEnvironmentUtil.outputEnvironmentVariable(env, SAUCE_API_KEY, getApiKey(), true, verboseLogging, listener.getLogger());
                /* New standard env name */
                SauceEnvironmentUtil.outputEnvironmentVariable(env, SAUCE_ACCESS_KEY, getApiKey(), true, verboseLogging, listener.getLogger());

                SauceEnvironmentUtil.outputEnvironmentVariable(env, SELENIUM_HOST, getHostName(), true, verboseLogging, listener.getLogger());
                if (StringUtils.isNotBlank(getNativeAppPackage())) {
                    SauceEnvironmentUtil.outputEnvironmentVariable(env, SAUCE_NATIVE_APP, getNativeAppPackage(), true, verboseLogging, listener.getLogger());
                }

                if (isEnableSauceConnect() && isUseGeneratedTunnelIdentifier()) {
                    SauceEnvironmentUtil.outputEnvironmentVariable(env, TUNNEL_IDENTIFIER, tunnelIdentifier, true, verboseLogging, listener.getLogger());
                }

                SauceEnvironmentUtil.outputEnvironmentVariable(env, SAUCE_USE_CHROME, String.valueOf(isUseChromeForAndroid()), true, verboseLogging, listener.getLogger());

                DecimalFormat myFormatter = new DecimalFormat("####");
                SauceEnvironmentUtil.outputEnvironmentVariable(env, SELENIUM_PORT, myFormatter.format(sauceConnectStarter != null ? sauceConnectStarter.port : getPort(env)), true, verboseLogging, listener.getLogger());
            }

            /**
             * @return the host name to be used for the build.
             */
            private String getHostName() {
                if (StringUtils.isNotBlank(seleniumHost)) {
                    Matcher matcher = ENVIRONMENT_VARIABLE_PATTERN.matcher(seleniumHost);
                    if (matcher.matches()) {
                        String variableName = matcher.group(1);
                        return System.getenv(variableName);
                    }
                    return seleniumHost;
                } else {
                    if (isEnableSauceConnect()) {
                        return getCurrentHostName();
                    } else {
                        return "ondemand.saucelabs.com";
                    }
                }
            }

            /**
             * {@inheritDoc}
             *
             * Terminates the Sauce Connect process (if the build is configured to launch Sauce Connect), and adds a {@link SauceOnDemandBuildAction} instance to the build.
             *  @param build
             *      The build in progress for which an {@link Environment} object is created.
             *      Never null.
             * @param listener
             *      Can be used to send any message.
             *
             */
            @Override
            public boolean tearDown(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {
                listener.getLogger().println("Starting post-build for Sauce Labs plugin");
                if (isEnableSauceConnect()) {
                    boolean shouldClose = true;
                    try {
                        if (condition != null) {
                            shouldClose = condition.runPerform(build, listener);
                        }
                    } catch (Exception e) {
                        listener.getLogger().println("Error checking Sauce Connect run condition");
                        throw new IOException(e);
                    }
                    if (shouldClose) {
                        listener.getLogger().println("Shutting down Sauce Connect");
                        String resolvedOptions = getCommandLineOptions(build, listener);

                        if (isUseGeneratedTunnelIdentifier()) {
                            build.getBuildVariables().put(TUNNEL_IDENTIFIER, tunnelIdentifier);
                            resolvedOptions = "--tunnel-identifier " + tunnelIdentifier + " " + resolvedOptions;
                        }

                        if (launchSauceConnectOnSlave) {
                            Computer.currentComputer().getChannel().call(new SauceConnectCloser(listener, getUserName(), resolvedOptions));
                        } else {
                            SauceConnectCloser tunnelCloser = new SauceConnectCloser(listener, getUserName(), resolvedOptions);
                            tunnelCloser.call();
                        }
                    }
                }

                processBuildOutput(build);
                logParserMap.remove(build.toString());
                listener.getLogger().println("Finished post-build for Sauce Labs plugin");
                return true;
            }
        };
    }

    /**
     * Returns the command line options to be used as part of Sauce Connect.  Any variable references contained in the
     * options specified within the Jenkins job configuration are resolved, and if common options are specified then these are appended to the list of options.
     *
     * @param build    The build in progress for which an {@link Environment} object is created.
     *                 Never null.
     * @param listener Can be used to send any message.
     * @return String representing the Sauce Connect command line options
     * @throws IOException
     * @throws InterruptedException
     */
    private String getCommandLineOptions(AbstractBuild build, BuildListener listener) throws IOException, InterruptedException {

        StringBuilder resolvedOptions = new StringBuilder();
        resolvedOptions.append(getResolvedOptions(build, listener, options));
        String resolvedCommonOptions = getResolvedOptions(build, listener, PluginImpl.get().getSauceConnectOptions());
        if (resolvedCommonOptions != null && !resolvedCommonOptions.equals("")) {
            if (!resolvedOptions.toString().equals("")) {
                resolvedOptions.append(' ');
            }
            resolvedOptions.append(resolvedCommonOptions);
        }
        return resolvedOptions.toString();
    }

    /**
     * Returns the Sauce Connect options, with any strings representing environment variables (eg. ${SOME_ENV_VAR}) resolved.
     *
     * @param build    The same {@link Build} object given to the set up method.
     * @param listener The same {@link BuildListener} object given to the set up method.
     * @param options  The command line options to resolve
     * @return the Sauce Connect options to be used for the build
     * @throws IOException
     * @throws InterruptedException
     */
    private String getResolvedOptions(AbstractBuild build, BuildListener listener, String options) throws IOException, InterruptedException {
        if (options == null) {
            return "";
        }
        VariableResolver.ByMap<String> variableResolver = new VariableResolver.ByMap<String>(build.getEnvironment(listener));
        return Util.replaceMacro(options, variableResolver);
    }

    /**
     * Adds a new {@link SauceOnDemandBuildAction} instance to the {@link AbstractBuild} instance. The
     * processing of the build output will be performed by the {@link SauceOnDemandReportPublisher} instance (which
     * is created if the 'Embed Sauce OnDemand Reports' option is selected.
     *
     * @param build the build in progress
     */
    private void processBuildOutput(AbstractBuild build) {
        if (logParserMap != null) {
            logger.fine("Adding build action to " + build.toString());
            SauceOnDemandLogParser logParser = logParserMap.get(build.toString());
            if (logParser != null) {
                SauceOnDemandBuildAction buildAction = new SauceOnDemandBuildAction(build, logParser, getUserName(), getApiKey());
                build.addAction(buildAction);
            }
        }
    }

    /**
     * Replace all spaces and hashes with underscores.
     *
     * @param buildNumber the current Jenkins build number
     * @return the build number with all non-alphanumeric characters replaced with _
     */
    public static String sanitiseBuildNumber(String buildNumber) {
        return buildNumber.replaceAll("[^A-Za-z0-9]", "_");
    }

    /**
     * @return the hostname for the current environment.
     */
    private static String getCurrentHostName() {
        try {
            String hostName = Computer.currentComputer().getHostName();
            if (hostName != null) {
                return hostName;
            }
        } catch (Exception e) {
            //shouldn't happen
            logger.log(Level.SEVERE, "Unable to retrieve host name", e);
        }
        return "localhost";
    }

    private static class GetAvailablePort implements Callable<Integer,RuntimeException> {
        public Integer call() {
            int foundPort = -1;
            java.net.ServerSocket socket = null;
            try {
                socket = new java.net.ServerSocket(0);
                foundPort = socket.getLocalPort();
            } catch (IOException e) {
            } finally {
                if (socket != null) try {
                    socket.close();
                } catch (IOException e) { /* e.printStackTrace(); */ }
            }
            return foundPort;
        }
        private static final long serialVersionUID = 1L;
    }

    /**
     * @return the port to be used
     */
    private int getPort(Map<String, String> envVars) {
        if (StringUtils.isNotBlank(seleniumPort) && !seleniumPort.equals("0")) {
            Matcher matcher = ENVIRONMENT_VARIABLE_PATTERN.matcher(seleniumPort);
            if (matcher.matches()) {
                String variableName = matcher.group(1);
                String port = envVars.get(variableName);
                if (port == null) {
                    port = System.getenv(variableName);
                }
                if (port == null) {
                    port = "0";
                }
                return Integer.parseInt(port);
            } else {
                return Integer.parseInt(seleniumPort);
            }
        } else {
            if (isEnableSauceConnect()) {
                if (isUseGeneratedTunnelIdentifier()) {
                    try {
                        if (launchSauceConnectOnSlave) {
                            return Computer.currentComputer().getChannel().call(new GetAvailablePort());
                        } else {
                            return new GetAvailablePort().call();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    return 0;
                }
                return 4445;
            } else {
                return 4444;
            }
        }
    }

    /**
     * @return the Sauce username to be used
     */
    public String getUserName() { return getCredentials().getUsername(); }

    /**
     * @return the Sauce access key to be used
     */
    public String getApiKey() { return getCredentials().getPassword().getPlainText(); }

    public boolean isUseLatestVersion() {
        return useLatestVersion;
    }

    public String getSeleniumHost() {
        return seleniumHost;
    }

    public void setSeleniumHost(String seleniumHost) {
        this.seleniumHost = seleniumHost;
    }

    public String getSeleniumPort() {
        return seleniumPort;
    }

    public void setSeleniumPort(String seleniumPort) {
        this.seleniumPort = seleniumPort;
    }

    public SauceCredentialsImpl getCredentials() {
        if (Strings.isNullOrEmpty(this.getCredentialId())) {
            return PluginImpl.get().getCredentials();
        }

        return SauceCredentialsImpl.getCredentialsById((Item) this, getCredentialId());
    }

    public SeleniumInformation getSeleniumInformation() {
        return seleniumInformation;
    }

    public void setSeleniumInformation(SeleniumInformation seleniumInformation) {
        this.seleniumInformation = seleniumInformation;
    }

    public boolean isEnableSauceConnect() {
        return enableSauceConnect;
    }

    public void setEnableSauceConnect(boolean enableSauceConnect) {
        this.enableSauceConnect = enableSauceConnect;
    }

    public List<String> getWebDriverBrowsers() {
        return webDriverBrowsers;
    }

    public void setWebDriverBrowsers(List<String> webDriverBrowsers) {
        this.webDriverBrowsers = webDriverBrowsers;
    }

    public List<String> getAppiumBrowsers() {
        return appiumBrowsers;
    }

    public void setAppiumBrowsers(List<String> appiumBrowsers) {
        this.appiumBrowsers = appiumBrowsers;
    }

    public boolean isLaunchSauceConnectOnSlave() {
        return launchSauceConnectOnSlave;
    }

    public void setLaunchSauceConnectOnSlave(boolean launchSauceConnectOnSlave) {
        this.launchSauceConnectOnSlave = launchSauceConnectOnSlave;
    }

    public boolean isUseGeneratedTunnelIdentifier() {
        return useGeneratedTunnelIdentifier;
    }

    public void setUseGeneratedTunnelIdentifier(boolean useGeneratedTunnelIdentifier) {
        this.useGeneratedTunnelIdentifier = useGeneratedTunnelIdentifier;
    }

    public boolean isVerboseLogging() {
        return verboseLogging;
    }

    public void setVerboseLogging(boolean verboseLogging) {
        this.verboseLogging = verboseLogging;
    }

    public String getOptions() {
        return options;
    }

    public void setOptions(String options) {
        this.options = options;
    }

    public RunCondition getCondition() {
        return condition;
    }

    public String getSauceConnectPath() {
        return sauceConnectPath;
    }

    public void setSauceConnectPath(String sauceConnectPath) {
        this.sauceConnectPath = sauceConnectPath;
    }

    public boolean isUseChromeForAndroid() {
        return useChromeForAndroid;
    }

    public String getNativeAppPackage() {
        return nativeAppPackage;
    }

    public void setUseLatestVersion(boolean useLatestVersion) {
        this.useLatestVersion = useLatestVersion;
    }

    public void setNativeAppPackage(String nativeAppPackage) {
        this.nativeAppPackage = nativeAppPackage;
    }

    public void setUseChromeForAndroid(boolean useChromeForAndroid) {
        this.useChromeForAndroid = useChromeForAndroid;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public void setCredentialId(String credentialId) { this.credentialId = credentialId; }

    /**
     * {@inheritDoc}
     *
     * Creates a new {@link SauceOnDemandLogParser} instance, which is added to the {@link #logParserMap}.
     */
    @Override
    public OutputStream decorateLogger(AbstractBuild build, OutputStream logger) throws IOException, InterruptedException, Run.RunnerAbortedException {
        SauceOnDemandLogParser sauceOnDemandLogParser = new SauceOnDemandLogParser(logger, build.getCharset());
        if (logParserMap == null) {
            logParserMap = new ConcurrentHashMap<String, SauceOnDemandLogParser>();
        }
        logParserMap.put(build.toString(), sauceOnDemandLogParser);
        return sauceOnDemandLogParser;
    }

    /**
     * Handles terminating any running Sauce Connect processes.
     */
    private static final class SauceConnectCloser implements Callable<SauceConnectCloser, AbstractSauceTunnelManager.SauceConnectException> {

        private final BuildListener listener;
        private final String username;
        private final String options;

        public SauceConnectCloser(final BuildListener listener, final String username, String options) {
            this.listener = listener;
            this.username = username;
            this.options = options;
        }

        /**
         * {@inheritDoc}
         * <p/>
         * Closes the Sauce Connect tunnel.
         */
        public SauceConnectCloser call() throws AbstractSauceTunnelManager.SauceConnectException {
            try {
                if (!StringUtils.isBlank(username)) {
                    getSauceTunnelManager().closeTunnelsForPlan(username, options, listener.getLogger());

                }
            } catch (ComponentLookupException e) {
                throw new AbstractSauceTunnelManager.SauceConnectException(e);
            }
            return this;
        }
    }

    /**
     * Handles starting Sauce Connect.
     */
    private static final class SauceConnectHandler implements Callable<SauceConnectHandler, AbstractSauceTunnelManager.SauceConnectException> {
        private final String options;
        private final String workingDirectory;
        private final String username;
        private final String key;

        private final BuildListener listener;
        private final boolean verboseLogging;
        private final String sauceConnectPath;
        private File sauceConnectJar;
        private int port;

        /**
         * @param sauceOnDemandBuildWrapper
         * @param listener
         * @param workingDirectory
         * @param resolvedOptions
         */
        public SauceConnectHandler(SauceOnDemandBuildWrapper sauceOnDemandBuildWrapper, EnvVars env, BuildListener listener, String workingDirectory, String resolvedOptions) {
            this.options = resolvedOptions;
            this.workingDirectory = workingDirectory;
            this.listener = listener;
            this.username = sauceOnDemandBuildWrapper.getUserName();
            this.port = sauceOnDemandBuildWrapper.getPort(env);
            this.key = sauceOnDemandBuildWrapper.getApiKey();
            this.verboseLogging = sauceOnDemandBuildWrapper.isVerboseLogging();
            this.sauceConnectPath = sauceOnDemandBuildWrapper.getSauceConnectPath();
        }

        /**
         * @param sauceOnDemandBuildWrapper
         * @param listener
         * @param workingDirectory
         * @param resolvedOptions
         * @param sauceConnectJar
         */
        public SauceConnectHandler(SauceOnDemandBuildWrapper sauceOnDemandBuildWrapper, EnvVars env, BuildListener listener, String workingDirectory, String resolvedOptions, File sauceConnectJar) {
            this(sauceOnDemandBuildWrapper, env, listener, workingDirectory, resolvedOptions);
            this.sauceConnectJar = sauceConnectJar;
        }

        /**
         * Launches Sauce Connect.
         *
         * @return the current SauceConnectHandler instance
         * @throws AbstractSauceTunnelManager.SauceConnectException
         *          thrown if an error occurs starting Sauce Connect
         */
        public SauceConnectHandler call() throws AbstractSauceTunnelManager.SauceConnectException {

            try {
                listener.getLogger().println("Launching Sauce Connect on " + getCurrentHostName());
                AbstractSauceTunnelManager sauceTunnelManager = getSauceTunnelManager();
                if (sauceTunnelManager instanceof HudsonSauceConnectFourManager && workingDirectory != null) {
                    ((HudsonSauceConnectFourManager) sauceTunnelManager).setWorkingDirectory(workingDirectory);
                }
                sauceTunnelManager.setSauceRest(new JenkinsSauceREST(username, key));
                if (StringUtils.isBlank(username)) {
                    listener.getLogger().println("Username not set, not starting Sauce Connect");

                } else if (StringUtils.isBlank(key)) {
                    listener.getLogger().println("Access key not set, not starting Sauce Connect");

                } else {
                    Process process = sauceTunnelManager.openConnection(username, key, port, sauceConnectJar, options, listener.getLogger(), verboseLogging, sauceConnectPath);
                }
                return this;
            } catch (ComponentLookupException e) {
                throw new AbstractSauceTunnelManager.SauceConnectException(e);
            }
        }
    }

    /**
     * Retrieve the {@link AbstractSauceTunnelManager} instance to be used to launch Sauce Connect.
     *
     * @return {@link AbstractSauceTunnelManager} instance
     * @throws ComponentLookupException see plexus
     */
    public static AbstractSauceTunnelManager getSauceTunnelManager() throws ComponentLookupException {
        return HudsonSauceManagerFactory.getInstance().createSauceConnectFourManager();
    }

    /**
     * Plugin descriptor, which adds the plugin details to the Jenkins job configuration page.
     */
    @Extension
    public static class DescriptorImpl extends Descriptor<BuildWrapper> {
        /**
         * @return text to be displayed within Jenkins job configuration
         */
        @Override
        public String getDisplayName() {
            return "Sauce Labs Support";
        }

        /**
         * @return the list of supported Appium browsers
         */
        public List<Browser> getAppiumBrowsers() {
            try {
                return PluginImpl.BROWSER_FACTORY.getAppiumBrowsers();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error retrieving browsers from Saucelabs", e);
            } catch (JSONException e) {
                logger.log(Level.SEVERE, "Error parsing JSON response", e);
            }
            return Collections.emptyList();
        }

        /**
         * @return the list of supported Selenium RC browsers
         */
        public List<Browser> getSeleniumBrowsers() {
            try {
                return PluginImpl.BROWSER_FACTORY.getSeleniumBrowsers();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error retrieving browsers from Saucelabs", e);
            } catch (JSONException e) {
                logger.log(Level.SEVERE, "Error parsing JSON response", e);
            }
            return Collections.emptyList();
        }

        /**
         * @return the list of supported WebDriver browsers
         */
        public List<Browser> getWebDriverBrowsers() {
            try {
                return PluginImpl.BROWSER_FACTORY.getWebDriverBrowsers();
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error retrieving browsers from Saucelabs", e);
            } catch (JSONException e) {
                logger.log(Level.SEVERE, "Error parsing JSON response", e);
            }
            return Collections.emptyList();
        }

        /**
         * @return the list of supported WebDriver browsers
         */
        public Map<String, List<Browser>> getWebDriverMap() {
            try {
                Map<String, List<Browser>> map = new HashMap<String, List<Browser>>();
                for (Browser browser : PluginImpl.BROWSER_FACTORY.getWebDriverBrowsers()) {
                    List<Browser> browsers = map.get(browser.getOs());
                    if (browsers == null) {
                        browsers = new ArrayList<Browser>();
                        map.put(browser.getOs(), browsers);
                    }
                    browsers.add(browser);
                }
                return map;
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error retrieving browsers from Saucelabs", e);
            } catch (JSONException e) {
                logger.log(Level.SEVERE, "Error parsing JSON response", e);
            }
            return Collections.emptyMap();
        }

        /**
         * @return the list of supported credentials
         */
        public ListBoxModel doFillCredentialIdItems(final @AncestorInPath ItemGroup<?> context) {
            return new SauceCredentialsListBoxModel()
                .withEmptySelection().withAll(SauceCredentialsImpl.all(context));
        }

    }


    /**
     * Captures the output of a Jenkins build, so that it can be parsed to find Sauce jobs invoked by the build.
     *
     * @author Ross Rowe
     */
    public class SauceOnDemandLogParser extends LineTransformationOutputStream implements Serializable {

        private transient OutputStream outputStream;
        private transient Charset charset;
        private List<String> lines;

        public SauceOnDemandLogParser(OutputStream outputStream, Charset charset) {
            this.outputStream = outputStream;
            this.charset = charset;
            this.lines = new ArrayList<String>();
        }

        /**
         * {@inheritDoc}
         *
         * Decodes the line and add it to the {@link #lines} list.
         */
        @Override
        protected void eol(byte[] b, int len) throws IOException {
            if (this.outputStream != null) {
                this.outputStream.write(b, 0, len);
            }
            if (charset != null) {
                lines.add(charset.decode(ByteBuffer.wrap(b, 0, len)).toString());
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() throws IOException {
            super.close();
            if (outputStream != null) {
                this.outputStream.close();
            }
        }

        public List<String> getLines() {
            return lines;
        }
    }

    protected void migrateCredentials(Project project) {
        if (Strings.isNullOrEmpty(this.credentialId) && this.credentials != null) {
            try {
                String credentialId = SauceCredentialsImpl.migrateToCredentials(
                    this.credentials.getUsername(),
                    this.credentials.getApiKey(),
                    project == null ? "Unknown" : project.getDisplayName()
                );
                this.credentialId = credentialId;
                this.credentials = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Extension
    static final public class ItemListenerImpl extends ItemListener {
        public void onLoaded() {
            for (Project p : Jenkins.getInstance().getItems(Project.class))
            {
                for (SauceOnDemandBuildWrapper bw : (List<SauceOnDemandBuildWrapper>) p.getBuildWrappersList().getAll(SauceOnDemandBuildWrapper.class))
                {
                    bw.migrateCredentials(p);
                }
            }
        }
    }
}