package org.cloudfoundry.identity.uaa.oauth.token;

import org.cloudfoundry.identity.uaa.oauth.common.exceptions.InvalidGrantException;
import org.cloudfoundry.identity.uaa.oauth.provider.ClientDetails;
import org.cloudfoundry.identity.uaa.oauth.provider.OAuth2Authentication;
import org.cloudfoundry.identity.uaa.oauth.provider.OAuth2Request;
import org.cloudfoundry.identity.uaa.oauth.provider.OAuth2RequestFactory;
import org.cloudfoundry.identity.uaa.oauth.provider.TokenRequest;
import org.cloudfoundry.identity.uaa.oauth.provider.token.AbstractTokenGranter;
import org.cloudfoundry.identity.uaa.oauth.provider.token.AuthorizationServerTokenServices;
import org.cloudfoundry.identity.uaa.provider.ClientRegistrationException;
import org.cloudfoundry.identity.uaa.security.beans.DefaultSecurityContextAccessor;
import org.cloudfoundry.identity.uaa.zone.MultitenantClientServices;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.GRANT_TYPE_TOKEN_EXCHANGE;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.TOKEN_EXCHANGE_IMPERSONATE_CLIENT_PERMISSION;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.TOKEN_TYPE_ACCESS;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.TOKEN_TYPE_ID;
import static org.cloudfoundry.identity.uaa.util.UaaStringUtils.hasText;

public class TokenExchangeGranter extends AbstractTokenGranter {

    final DefaultSecurityContextAccessor defaultSecurityContextAccessor;

    private final MultitenantClientServices clientDetailsService;

    public TokenExchangeGranter(AuthorizationServerTokenServices tokenServices,
                                MultitenantClientServices clientDetailsService,
                                OAuth2RequestFactory requestFactory) {
        super(tokenServices, clientDetailsService, requestFactory, GRANT_TYPE_TOKEN_EXCHANGE);
        defaultSecurityContextAccessor = new DefaultSecurityContextAccessor();
        this.clientDetailsService = clientDetailsService;
    }

    protected Authentication validateRequest(TokenRequest request) {
        if (defaultSecurityContextAccessor.isUser()) {
            if (request == null ||
                    request.getRequestParameters() == null ||
                    request.getRequestParameters().isEmpty()) {
                throw new InvalidGrantException("Missing subject token request object");
            }
            if (request.getRequestParameters().get("grant_type") == null) {
                throw new InvalidGrantException("Missing grant type");
            }
            if (!GRANT_TYPE_TOKEN_EXCHANGE.equals(request.getRequestParameters().get("grant_type"))) {
                throw new InvalidGrantException("Invalid grant type");
            }
            String subjectToken = request.getRequestParameters().get("subject_token");
            if (subjectToken == null || !hasText(subjectToken)) {
                throw new InvalidGrantException("Missing subject token");
            }
            String subjectTokenType = request.getRequestParameters().get("subject_token_type");
            if (subjectTokenType == null) {
                throw new InvalidGrantException("Missing subject token type");
            }
            if (! (TOKEN_TYPE_ID.equals(subjectTokenType) || TOKEN_TYPE_ACCESS.equals(subjectTokenType)) ) {
                throw new InvalidGrantException("Invalid subject token type, only " + TOKEN_TYPE_ID +
                        " and " + TOKEN_TYPE_ACCESS + " are supported");
            }
            String requestedTokenType = request.getRequestParameters().get("requested_token_type");
            if (hasText(requestedTokenType) && !TOKEN_TYPE_ACCESS.equals(requestedTokenType)) {
                throw new InvalidGrantException("Invalid requested token type, only " + TOKEN_TYPE_ACCESS + " is supported");
            }

            String audience = request.getRequestParameters().get("audience");
            if (hasText(audience)) {
                ClientDetails client;
                try {
                    client = clientDetailsService.loadClientByClientId(request.getClientId());
                } catch (ClientRegistrationException e) {
                    throw new InvalidGrantException("Invalid client_id");
                }
                try {
                    clientDetailsService.loadClientByClientId(audience);
                } catch (ClientRegistrationException e) {
                    throw new InvalidGrantException("Invalid audience");
                }
                long count = client.getAuthorities()==null ? 0l : client
                        .getAuthorities()
                        .stream()
                        .filter(ga -> TOKEN_EXCHANGE_IMPERSONATE_CLIENT_PERMISSION.equals(ga.getAuthority()))
                        .count();
                if (count == 0) {
                    throw new InvalidGrantException(
                            "Insufficient permissions, " + TOKEN_EXCHANGE_IMPERSONATE_CLIENT_PERMISSION +
                                    " is missing."
                    );

                }
            }
        } else {
            throw new InvalidGrantException("User authentication not found");
        }
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Override
    protected OAuth2Authentication getOAuth2Authentication(ClientDetails client, TokenRequest tokenRequest) {
        Authentication userAuth = validateRequest(tokenRequest);
        OAuth2Request storedOAuth2Request = getRequestFactory().createOAuth2Request(client, tokenRequest);
        return new OAuth2Authentication(storedOAuth2Request, userAuth);
    }
}
