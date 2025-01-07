package org.cloudfoundry.identity.uaa.provider.saml;

import lombok.extern.slf4j.Slf4j;
import net.shibboleth.utilities.java.support.xml.ParserPool;
import org.cloudfoundry.identity.uaa.provider.SamlIdentityProviderDefinition;
import org.opensaml.core.config.ConfigurationService;
import org.opensaml.core.xml.XMLObject;
import org.opensaml.core.xml.config.XMLObjectProviderRegistry;
import org.opensaml.core.xml.schema.XSAny;
import org.opensaml.core.xml.schema.XSBase64Binary;
import org.opensaml.core.xml.schema.XSBoolean;
import org.opensaml.core.xml.schema.XSBooleanValue;
import org.opensaml.core.xml.schema.XSDateTime;
import org.opensaml.core.xml.schema.XSInteger;
import org.opensaml.core.xml.schema.XSQName;
import org.opensaml.core.xml.schema.XSString;
import org.opensaml.core.xml.schema.XSURI;
import org.opensaml.saml.saml2.core.Assertion;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Response;
import org.opensaml.saml.saml2.core.impl.AssertionUnmarshaller;
import org.opensaml.saml.saml2.core.impl.AuthnRequestUnmarshaller;
import org.opensaml.saml.saml2.core.impl.ResponseUnmarshaller;
import org.springframework.security.saml2.core.OpenSamlInitializationService;

import javax.xml.namespace.QName;
import java.time.Instant;

@Slf4j
public final class OpenSamlXmlUtils {

    static {
        OpenSamlInitializationService.initialize();
    }

    static {
        XMLObjectProviderRegistry registry = ConfigurationService.get(XMLObjectProviderRegistry.class);
        authnRequestUnmarshaller = (AuthnRequestUnmarshaller) registry.getUnmarshallerFactory()
                .getUnmarshaller(AuthnRequest.DEFAULT_ELEMENT_NAME);
        responseUnmarshaller = (ResponseUnmarshaller) registry.getUnmarshallerFactory()
                .getUnmarshaller(Response.DEFAULT_ELEMENT_NAME);
        assertionUnmarshaller = (AssertionUnmarshaller) registry.getUnmarshallerFactory()
                .getUnmarshaller(Assertion.DEFAULT_ELEMENT_NAME);
        parserPool = registry.getParserPool();
    }

    private static final ResponseUnmarshaller responseUnmarshaller;
    private static final AuthnRequestUnmarshaller authnRequestUnmarshaller;
    private static final AssertionUnmarshaller assertionUnmarshaller;

    private static final ParserPool parserPool;

    public static boolean initialize() {
        return parserPool != null;
    }

    protected static ParserPool getParserPool() {
        return parserPool;
    }

    protected static AuthnRequestUnmarshaller getAuthnRequestUnmarshaller() {
        return authnRequestUnmarshaller;
    }

    protected static ResponseUnmarshaller getResponseUnmarshaller() {
        return responseUnmarshaller;
    }

    protected static AssertionUnmarshaller getAssertionUnmarshaller() {
        return assertionUnmarshaller;
    }

    private OpenSamlXmlUtils() {
        throw new java.lang.UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static String getStringValue(String key, SamlIdentityProviderDefinition definition, XMLObject xmlObject) {
        String value = null;
        if (xmlObject instanceof XSString xsString) {
            value = xsString.getValue();
        } else if (xmlObject instanceof XSAny xsAny) {
            value = xsAny.getTextContent();
        } else if (xmlObject instanceof XSInteger xsInteger) {
            Integer i = xsInteger.getValue();
            value = i != null ? i.toString() : null;
        } else if (xmlObject instanceof XSBoolean xsBoolean) {
            XSBooleanValue b = xsBoolean.getValue();
            value = b != null && b.getValue() != null ? b.getValue().toString() : null;
        } else if (xmlObject instanceof XSDateTime xsDateTime) {
            Instant d = xsDateTime.getValue();
            value = d != null ? d.toString() : null;
        } else if (xmlObject instanceof XSQName xsQName) {
            QName name = xsQName.getValue();
            value = name != null ? name.toString() : null;
        } else if (xmlObject instanceof XSURI xsUri) {
            value = xsUri.getURI();
        } else if (xmlObject instanceof XSBase64Binary xsBase64Binary) {
            value = xsBase64Binary.getValue();
        }

        if (value != null) {
            log.debug("Found SAML user attribute {} of value {} [zone:{}, origin:{}]", key, value, definition.getZoneId(), definition.getIdpEntityAlias());
            return value;
        } else if (xmlObject != null) {
            log.debug("SAML user attribute {} at is not of type XSString or other recognizable type, {} [zone:{}, origin:{}]", key, xmlObject.getClass().getName(), definition.getZoneId(), definition.getIdpEntityAlias());
        }
        return null;
    }
}
