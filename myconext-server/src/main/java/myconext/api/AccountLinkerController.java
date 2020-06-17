package myconext.api;


import myconext.exceptions.UserNotFoundException;
import myconext.manage.ServiceNameResolver;
import myconext.model.LinkedAccount;
import myconext.model.SamlAuthenticationRequest;
import myconext.model.StepUpStatus;
import myconext.model.User;
import myconext.repository.AuthenticationRequestRepository;
import myconext.repository.UserRepository;
import myconext.security.ACR;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/myconext/api")
public class AccountLinkerController {

    private static final Logger LOG = LoggerFactory.getLogger(AccountLinkerController.class);

    private final String oidcBaseUrl;
    private final String clientId;
    private final String clientSecret;
    private final String idpFlowRedirectUri;
    private final String spFlowRedirectUri;
    private final RestTemplate restTemplate = new RestTemplate();
    private final HttpHeaders headers = new HttpHeaders();
    private final AuthenticationRequestRepository authenticationRequestRepository;
    private final ServiceNameResolver serviceNameResolver;
    private final UserRepository userRepository;
    private final String magicLinkUrl;
    private final String idpErrorRedirectUrl;
    private final String spRedirectUrl;
    private final long expiryNonValidatedDurationDays;
    private final long expiryValidatedDurationDays;

    public AccountLinkerController(
            AuthenticationRequestRepository authenticationRequestRepository,
            UserRepository userRepository,
            ServiceNameResolver serviceNameResolver,
            @Value("${email.magic-link-url}") String magicLinkUrl,
            @Value("${idp_redirect_url}") String idpErrorRedirectUrl,
            @Value("${sp_redirect_url}") String spRedirectUrl,
            @Value("${oidc.client-id}") String clientId,
            @Value("${oidc.secret}") String clientSecret,
            @Value("${oidc.idp-flow-redirect-url}") String idpFlowRedirectUri,
            @Value("${oidc.sp-flow-redirect-url}") String spFlowRedirectUri,
            @Value("${oidc.base-url}") String oidcBaseUrl,
            @Value("${oidc.expiry-duration-days-non-validated}") long expiryNonValidatedDurationDays,
            @Value("${oidc.expiry-duration-days-validated}") long expiryValidatedDurationDays) {
        this.authenticationRequestRepository = authenticationRequestRepository;
        this.userRepository = userRepository;
        this.serviceNameResolver = serviceNameResolver;
        this.magicLinkUrl = magicLinkUrl;
        this.idpErrorRedirectUrl = idpErrorRedirectUrl;
        this.spRedirectUrl = spRedirectUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.idpFlowRedirectUri = idpFlowRedirectUri;
        this.spFlowRedirectUri = spFlowRedirectUri;
        this.oidcBaseUrl = oidcBaseUrl;
        this.expiryNonValidatedDurationDays = expiryNonValidatedDurationDays;
        this.expiryValidatedDurationDays = expiryValidatedDurationDays;

        this.headers.set("Accept", "application/json");
    }

    @GetMapping("/idp/oidc/account/{id}")
    public ResponseEntity startIdPLinkAccountFlow(@PathVariable("id") String id,
                                                  @RequestParam(value = "forceAuth", required = false, defaultValue = "false") boolean forceAuth) {
        LOG.info("Start IdP link account flow for SAML Authentication Request ID " + id);

        UriComponents uriComponents = doStartLinkAccountFlow(id, idpFlowRedirectUri, forceAuth);
        return ResponseEntity.status(HttpStatus.FOUND).location(uriComponents.toUri()).build();
    }

    @GetMapping("/sp/oidc/link")
    public ResponseEntity startSPLinkAccountFlow(Authentication authentication) {
        User user = (User) authentication.getPrincipal();

        LOG.info("Start link account flow for authentication " + user.getEmail());

        UriComponents uriComponents = doStartLinkAccountFlow("state", spFlowRedirectUri, true);
        return ResponseEntity.ok(Collections.singletonMap("url", uriComponents.toUriString()));
    }

    private UriComponents doStartLinkAccountFlow(String state, String redirectUri, boolean forceAuth) {
        Map<String, String> params = new HashMap<>();

        params.put("client_id", clientId);
        params.put("response_type", "code");
        params.put("scope", "openid");
        params.put("redirect_uri", redirectUri);
        params.put("state", state);
        if (forceAuth) {
            params.put("prompt", "login");
        }

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(oidcBaseUrl + "/oidc/authorize");
        params.forEach(builder::queryParam);
        return builder.build();
    }

    @GetMapping("/sp/oidc/redirect")
    public ResponseEntity spFlowRedirect(Authentication authentication, @RequestParam("code") String code, @RequestParam("state") String state) {
        User principal = (User) authentication.getPrincipal();
        User user = userRepository.findOneUserByEmailIgnoreCase(principal.getEmail());

        LOG.info("In SP redirect for link account flow for authentication " + user.getEmail());

        return doRedirect(code, user, this.spFlowRedirectUri, this.spRedirectUrl + "/institutions",
                false, false, null);
    }

    @GetMapping("/idp/oidc/redirect")
    public ResponseEntity idpFlowRedirect(@RequestParam("code") String code, @RequestParam("state") String state) throws UnsupportedEncodingException {
        Optional<SamlAuthenticationRequest> optionalSamlAuthenticationRequest = authenticationRequestRepository.findByIdAndNotExpired(state);
        if (!optionalSamlAuthenticationRequest.isPresent()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(this.idpErrorRedirectUrl + "/expired")).build();
        }
        SamlAuthenticationRequest samlAuthenticationRequest = optionalSamlAuthenticationRequest.get();
        User user = userRepository.findById(samlAuthenticationRequest.getUserId()).orElseThrow(UserNotFoundException::new);

        boolean validateNames = samlAuthenticationRequest.getAuthenticationContextClassReferences().contains(ACR.VALIDATE_NAMES);
        boolean studentAffiliationRequired = samlAuthenticationRequest.getAuthenticationContextClassReferences().contains(ACR.AFFILIATION_STUDENT);

        LOG.info("In IdP redirect for link account flow for user " + user);

        String location = this.magicLinkUrl + "?h=" + samlAuthenticationRequest.getHash();

        String charSet = Charset.defaultCharset().name();
        String serviceName = serviceNameResolver.resolve(samlAuthenticationRequest.getRequesterEntityId());

        String idpStudentAffiliationRequiredUri = this.idpErrorRedirectUrl + "/affiliation-missing/" +
                samlAuthenticationRequest.getId() +
                "?h=" + samlAuthenticationRequest.getHash() +
                "&redirect=" + URLEncoder.encode(this.magicLinkUrl, charSet) +
                "&name=" + URLEncoder.encode(serviceName, charSet);

        ResponseEntity redirect = doRedirect(code, user, this.idpFlowRedirectUri, location, validateNames, studentAffiliationRequired,
                idpStudentAffiliationRequiredUri);

        StepUpStatus stepUpStatus = redirect.getHeaders().getLocation()
                .toString().contains("affiliation-missing") ? StepUpStatus.MISSING_AFFILIATION : StepUpStatus.IN_STEP_UP;
        samlAuthenticationRequest.setSteppedUp(stepUpStatus);
        authenticationRequestRepository.save(samlAuthenticationRequest);

        return redirect;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity doRedirect(@RequestParam("code") String code, User user, String oidcRedirectUri,
                                      String clientRedirectUri, boolean validateNames, boolean studentAffiliationRequired,
                                      String idpStudentAffiliationRequiredUri) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> map = new LinkedMultiValueMap<>();
        map.add("client_id", clientId);
        map.add("client_secret", clientSecret);
        map.add("code", code);
        map.add("grant_type", "authorization_code");
        map.add("redirect_uri", oidcRedirectUri);
        map.add("scope", "openid");

        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(map, headers);

        Map<String, Object> body = restTemplate.exchange(oidcBaseUrl + "/oidc/token", HttpMethod.POST, request, new ParameterizedTypeReference<Map<String, Object>>() {
        }).getBody();

        map = new LinkedMultiValueMap<>();
        map.add("access_token", (String) body.get("access_token"));

        request = new HttpEntity<>(map, headers);

        body = restTemplate.exchange(oidcBaseUrl + "/oidc/userinfo", HttpMethod.POST, request, new ParameterizedTypeReference<Map<String, Object>>() {
        }).getBody();

        String eppn = (String) body.get("eduperson_principal_name");
        String surfCrmId = (String) body.get("surf-crm-id");
        String schacHomeOrganization = (String) body.get("schac_home_organization");

        String givenName = validateNames ? (String) body.get("given_name") : null;
        String familyName = validateNames ? (String) body.get("family_name") : null;

        String institutionIdentifier = StringUtils.hasText(surfCrmId) ? surfCrmId : schacHomeOrganization;

        List<String> eduPersonAffiliations = (List<String>) body.getOrDefault("eduperson_affiliation", new ArrayList<>());
        List<String> eduPersonScopedAffiliations = (List<String>) body.getOrDefault("eduperson_scoped_affiliation", eduPersonAffiliations);
        List<String> affiliations = CollectionUtils.isEmpty(eduPersonScopedAffiliations)
                ? Arrays.asList("affiliate") : eduPersonScopedAffiliations;

        if (StringUtils.hasText(schacHomeOrganization)) {
            Date expiresAt = Date.from(new Date().toInstant()
                    .plus(validateNames ? this.expiryValidatedDurationDays : this.expiryNonValidatedDurationDays, ChronoUnit.DAYS));
            List<LinkedAccount> linkedAccounts = user.getLinkedAccounts();
            Optional<LinkedAccount> optionalLinkedAccount = linkedAccounts.stream()
                    .filter(linkedAccount -> linkedAccount.getSchacHomeOrganization().equals(schacHomeOrganization))
                    .findFirst();
            if (optionalLinkedAccount.isPresent()) {
                optionalLinkedAccount.get().updateExpiresIn(institutionIdentifier, affiliations, givenName, familyName, expiresAt);
            } else {
                linkedAccounts.add(
                        new LinkedAccount(institutionIdentifier, schacHomeOrganization, eppn, givenName, familyName, affiliations,
                                new Date(), expiresAt));
            }
            String action = optionalLinkedAccount.isPresent() ? "updated" : "created";
            String eppnValue = StringUtils.hasText(eppn) ? String.format("eppn %s", eppn) : "NO eppn";

            LOG.info("An account link has been {} for User {} with {} to institution {} with the affiliations {}",
                    action, user.getEmail(), eppnValue, institutionIdentifier, affiliations);

            userRepository.save(user);
        } else {
            LOG.error(String.format("User %s has linked his account, but no institutional identifier or EPPN was provided by the IdP", user.getEmail()));
        }
        boolean hasStudentAffiliation = user.getLinkedAccounts().stream()
                .anyMatch(linkedAccount ->
                        (CollectionUtils.isEmpty(linkedAccount.getEduPersonAffiliations()) ? Collections.<String>emptyList() : linkedAccount.getEduPersonAffiliations())
                                .stream()
                                .anyMatch(affiliation -> affiliation.startsWith("student")));
        if (studentAffiliationRequired && !hasStudentAffiliation) {
            //Corner case where the user has been stepped Up, but there is no student affiliation
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(idpStudentAffiliationRequiredUri)).build();
        }

        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(clientRedirectUri)).build();
    }

}