/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.message.BasicNameValuePair;
import org.auraframework.Aura;
import org.auraframework.def.DefDescriptor;
import org.auraframework.def.DefDescriptor.DefType;
import org.auraframework.def.DescriptorFilter;
import org.auraframework.def.TestCaseDef;
import org.auraframework.def.TestSuiteDef;
import org.auraframework.service.ContextService;
import org.auraframework.service.DefinitionService;
import org.auraframework.system.AuraContext.Authentication;
import org.auraframework.system.AuraContext.Format;
import org.auraframework.system.AuraContext.Mode;
import org.auraframework.test.WebDriverUtil.BrowserType;
import org.auraframework.test.annotation.UnAdaptableTest;
import org.auraframework.test.annotation.WebDriverTest;
import org.auraframework.throwable.quickfix.QuickFixException;
import org.auraframework.util.AuraTextUtil;
import org.auraframework.util.json.Json;
import org.auraframework.util.json.JsonReader;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * TODO(W-1386863): investigate why/fix the thread hostile nature of these tests.
 */
@UnAdaptableTest
@WebDriverTest
public class ComponentJSTestSuiteTest extends TestSuite {

    public static TestSuite suite() throws Exception {
        final TestSuite suite = new NamespaceTestSuite("*");
        suite.setName("JS Component Tests");
        return suite;
    }

    private static class FailTestCase extends WebDriverTestCase {
        private final Throwable cause;

        private FailTestCase(DefDescriptor<TestSuiteDef> descriptor, Throwable cause) {
            super(descriptor.getQualifiedName());
            this.cause = cause;
        }

        @Override
        public void runTest() throws Throwable {
            throw cause;
        }
    }

    @UnAdaptableTest
    public static class NamespaceTestSuite extends TestSuite {
        public NamespaceTestSuite(String namespace) throws Exception {
            super(namespace);
            final ContextService contextService = Aura.getContextService();
            final DefinitionService definitionService = Aura.getDefinitionService();

            boolean contextStarted = false;
            if (!contextService.isEstablished()) {
                contextStarted = true;
                contextService.startContext(Mode.JSTEST, Format.JSON, Authentication.AUTHENTICATED);
            }

            final Map<String, TestSuite> subSuites = new HashMap<>();
            try {
                final DescriptorFilter filter = new DescriptorFilter("js://"+namespace, DefType.TESTSUITE.toString());
                final Set<DefDescriptor<?>> descriptors = definitionService.find(filter);

                for (final DefDescriptor<?> qd  : descriptors) {
                    @SuppressWarnings("unchecked")
                    final
                    DefDescriptor<TestSuiteDef> descriptor = (DefDescriptor<TestSuiteDef>)qd;
                    Test test;
                    try {
                        test = new ComponentTestSuite(descriptor.getDef());
                    } catch (final Throwable t) {
                        test = new FailTestCase(descriptor, t);
                    }
                    final String testNamespace = descriptor.getNamespace();
                    if (namespace.equals(testNamespace)) {
                        addTest(test);
                    } else {
                        TestSuite subSuite = subSuites.get(testNamespace);
                        if (subSuite == null) {
                            subSuite = new TestSuite(testNamespace);
                            subSuites.put(testNamespace, subSuite);
                            addTest(subSuite);
                        }
                        subSuite.addTest(test);
                    }
                }
            } catch (final Throwable t) {
                System.err.println("Failed to load component tests for namespace: " + namespace);
                t.printStackTrace();
            } finally {
                if (contextStarted) {
                    contextService.endContext();
                }
            }
        }
    }

    private static class ComponentTestSuite extends TestSuite {

        private final DefDescriptor<TestSuiteDef> descriptor;

        private ComponentTestSuite(TestSuiteDef suiteDef) {
            super(String.format("%s:%sTest.js", suiteDef.getDescriptor().getNamespace(), suiteDef.getDescriptor().getName()));

            this.descriptor = suiteDef.getDescriptor();
            for (final TestCaseDef caseDef : suiteDef.getTestCaseDefs()) {
                addTest(new ComponentTestCase(this, caseDef));
            }
        }

        public String getUrl(DefType defType) {
            return getUrl(defType, Mode.AUTOJSTEST);
        }

        public String getUrl(DefType defType, Mode mode) {
            String ext = ".cmp";
            if (defType == DefType.APPLICATION) {
                ext = ".app";
            }
            return String.format("/%s/%s%s?aura.mode=%s&aura.testReset=true", descriptor.getNamespace(),
                    descriptor.getName(), ext, mode);
        }

        /**
         * @return Returns the code.
         * @throws QuickFixException
         */
        public String getCode() throws QuickFixException {
            final ContextService contextService = Aura.getContextService();
            final boolean isEstablished = contextService.isEstablished();
            if (!isEstablished) {
                contextService.startContext(Mode.AUTOJSTEST, Format.JSON, Authentication.AUTHENTICATED);
            }
            try {
                return descriptor.getDef().getCode();
            } finally {
                if (!isEstablished) {
                    contextService.endContext();
                }
            }
        }
    }

    public static class ComponentTestCase extends WebDriverTestCase {
        private ComponentTestCase(ComponentTestSuite suite, TestCaseDef caseDef) {
            super(caseDef.getName());
            this.name = caseDef.getName();
            this.suite = suite;
            this.caseDef = caseDef;
            for (final String browser : caseDef.getBrowsers()) {
                String token = browser.trim().toUpperCase();
                Set<BrowserType> set;
                if (token.charAt(0) == '-') {
                    token = token.substring(1);
                    set = excludedBrowsers;
                } else {
                    set = targetBrowsers;
                }
                try {
                    set.add(BrowserType.valueOf(token));
                } catch (final IllegalArgumentException e) {
                    fail("Unknown BrowserType: " + browser);
                }
            }
        }

        @Override
        public String toString() {
            return getName() + "(" + suite.getName() + ")";
        }

        private String getUrl() {
            final DefType defType = caseDef.getDefType();
            final String baseUrl = suite.getUrl(defType);
            final Set<Entry<String, Object>> attributes = caseDef.getAttributeValues().entrySet();
            if (attributes.isEmpty()) {
                return baseUrl;
            } else {
                String hash = "";
                final List<NameValuePair> newParams = Lists.newArrayList();
                for (final Entry<String, Object> entry : attributes) {
                    final String key = entry.getKey();
                    String value;
                    if(entry.getValue() instanceof Map<?, ?> || entry.getValue() instanceof List<?>) {
                        value = Json.serialize(entry.getValue());
                    } else {
                        value = entry.getValue().toString();
                    }
                    if (key.equals("__layout")) {
                        hash = value;
                    } else {
                        newParams.add(new BasicNameValuePair(key, value));
                    }
                }
                return baseUrl + "&" + URLEncodedUtils.format(newParams, "UTF-8") + hash;
            }
        }

        public String getTestUrlForManualRun() {
            return suite.getUrl(caseDef.getDefType(), Mode.JSTEST) + "&test=" + caseDef.getName();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getQualifiedName() {
            final String btype = getBrowserTypeString();
            return caseDef.getDescriptor().getQualifiedName() + btype;
        }

        public void testRun() throws Throwable {
            String validationError = "";

            try {
                caseDef.validateDefinition();
            } catch (final QuickFixException qfe) {
                validationError = ", '"+AuraTextUtil.escapeForJavascriptString(qfe.getMessage())+"'";
            }
            addMocksToTestContextLocalDef(caseDef.getLocalDefs());

            open(getUrl(), Mode.AUTOJSTEST);

            final String ret = (String) auraUITestingUtil.getEval(String.format(
                    "return window.aura.test.run('%s', '%s', 30%s)",
                    AuraTextUtil.escapeForJavascriptString(caseDef.getName()),
                    AuraTextUtil.escapeForJavascriptString(suite.getCode()), validationError));

            if (ret != null && !"null".equals(ret)) {
                @SuppressWarnings("unchecked")
                final
                Map<String, Object> e = (Map<String, Object>) new JsonReader()
                .read(ret);
                fail((String) e.get("message"));
            }
            // Actions run on servers need special handling because their call
            // back methods are called asynchronously.
            // This check is to make sure all such calls were complete
            waitForCondition("return window.aura.test.isComplete()", 30);
        }

        @Override
        public Set<BrowserType> getTargetBrowsers() {
            return targetBrowsers;
        }

        @Override
        public Set<BrowserType> getExcludedBrowsers() {
            return excludedBrowsers;
        }

        @Override
        protected Set<String> getAuraErrorsExpectedDuringInit() {
            return caseDef.getAuraErrorsExpectedDuringInit();
        }

        @Override
        public Set<String> getTestLabels() {
            return Sets.newHashSet(caseDef.getTestLabels());
        }

        private final ComponentTestSuite suite;
        private final TestCaseDef caseDef;
        private final String name;
        private final Set<BrowserType> targetBrowsers = EnumSet.noneOf(BrowserType.class);
        private final Set<BrowserType> excludedBrowsers = EnumSet.noneOf(BrowserType.class);
    }
}
