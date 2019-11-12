package surfid.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.saml.provider.SamlServerConfiguration;
import org.springframework.security.saml.provider.config.SamlConfigurationRepository;
import org.springframework.security.saml.provider.identity.ResponseEnhancer;
import org.springframework.security.saml.provider.identity.config.SamlIdentityProviderServerBeanConfiguration;
import surfid.repository.AuthenticationRequestRepository;
import surfid.repository.UserRepository;
import surfid.saml.ImmutableSamlConfigurationRepository;
import surfid.security.GuestIdpAuthenticationRequestFilter;

import javax.servlet.Filter;

@Configuration
public class BeanConfig extends SamlIdentityProviderServerBeanConfiguration {

    private final String redirectUrl;
    private final AuthenticationRequestRepository authenticationRequestRepository;
    private final UserRepository userRepository;
    private final String spEntityId;
    private final int rememberMeMaxAge;
    private final boolean secureCookie;
    private final String magicLinkUrl;

    public BeanConfig(@Value("${base_path}") String basePath,
                      @Value("${redirect_url}") String redirectUrl,
                      @Value("${sp_entity_id}") String spEntityId,
                      @Value("${remember_me_max_age_seconds}") int rememberMeMaxAge,
                      @Value("${secure_cookie}") boolean secureCookie,
                      @Value("${email.magic-link-url}") String magicLinkUrl,
                      AuthenticationRequestRepository authenticationRequestRepository,
                      UserRepository userRepository) {
        this.immutableSamlConfigurationRepository = new ImmutableSamlConfigurationRepository(basePath);
        this.redirectUrl = redirectUrl;
        this.spEntityId = spEntityId;
        this.rememberMeMaxAge = rememberMeMaxAge;
        this.secureCookie = secureCookie;
        this.authenticationRequestRepository = authenticationRequestRepository;
        this.userRepository = userRepository;
        this.magicLinkUrl = magicLinkUrl;
    }

    private ImmutableSamlConfigurationRepository immutableSamlConfigurationRepository;

    @Override
    protected SamlServerConfiguration getDefaultHostSamlServerConfiguration() {
        return new SamlServerConfiguration();
    }

    @Override
    public Filter idpAuthnRequestFilter() {
        return new GuestIdpAuthenticationRequestFilter(getSamlProvisioning(), samlAssertionStore(), redirectUrl,
                authenticationRequestRepository, userRepository, spEntityId, rememberMeMaxAge, secureCookie, magicLinkUrl);
    }

    public Filter samlConfigurationFilter(SamlServerConfiguration serverConfig) {
        this.immutableSamlConfigurationRepository.setConfiguration(serverConfig);
        return new NoopFilter();
    }

    @Override
    public SamlConfigurationRepository samlConfigurationRepository() {
        return immutableSamlConfigurationRepository;
    }

}
