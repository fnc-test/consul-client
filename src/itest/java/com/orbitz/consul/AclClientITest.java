package com.orbitz.consul;

import static com.orbitz.consul.TestUtils.randomUUIDString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatExceptionOfType;

import com.google.common.net.HostAndPort;
import com.orbitz.consul.model.acl.ImmutablePolicy;
import com.orbitz.consul.model.acl.ImmutablePolicyLink;
import com.orbitz.consul.model.acl.ImmutableRole;
import com.orbitz.consul.model.acl.ImmutableRolePolicyLink;
import com.orbitz.consul.model.acl.ImmutableToken;
import com.orbitz.consul.model.acl.PolicyResponse;
import com.orbitz.consul.model.acl.RoleResponse;
import com.orbitz.consul.model.acl.Token;
import com.orbitz.consul.model.acl.TokenResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

class AclClientITest {

    public static GenericContainer<?> consulContainerAcl;

    static {
        // noinspection resource
        consulContainerAcl = new GenericContainer<>("consul")
                .withCommand("agent", "-dev", "-client", "0.0.0.0", "--enable-script-checks=true")
                .withExposedPorts(8500)
                .withEnv("CONSUL_LOCAL_CONFIG",
                        "{\n" +
                                "  \"acl\": {\n" +
                                "    \"enabled\": true,\n" +
                                "    \"default_policy\": \"deny\",\n" +
                                "    \"tokens\": {\n" +
                                "      \"master\": \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"\n" +
                                "    }\n" +
                                "  }\n" +
                                "}"
                );
        consulContainerAcl.start();
    }

    protected static Consul client;

    protected static HostAndPort aclClientHostAndPort = HostAndPort.fromParts("localhost", consulContainerAcl.getFirstMappedPort());

    private AclClient aclClient;

    @BeforeAll
    static void beforeClass() {
        client = Consul.builder()
                .withHostAndPort(aclClientHostAndPort)
                .withAclToken("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                .withReadTimeoutMillis(Duration.ofSeconds(2).toMillis())
                .build();
    }

    @BeforeEach
    void setUp() {
        aclClient = client.aclClient();
    }

    @Test
    void listPolicies() {
        assertThat(aclClient.listPolicies().stream().anyMatch(p -> Objects.equals(p.name(), "global-management"))).isTrue();
    }

    @Test
    void testCreateAndReadPolicy() {
        var policyName = randomUUIDString();
        PolicyResponse policy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());
        assertThat(policy.name()).isEqualTo(policyName);
        assertThat(policy.datacenters()).isEmpty();

        policy = aclClient.readPolicy(policy.id());
        assertThat(policy.name()).isEqualTo(policyName);
        assertThat(policy.datacenters()).isEmpty();
    }

    @Test
    void testCreateAndReadPolicy_WithDatacenters() {
        var policyName = randomUUIDString();
        ImmutablePolicy newPolicy = ImmutablePolicy.builder().name(policyName).datacenters(List.of("dc1")).build();
        PolicyResponse policy = aclClient.createPolicy(newPolicy);
        assertThat(policy.name()).isEqualTo(policyName);
        assertThat(policy.datacenters()).contains(List.of("dc1"));

        policy = aclClient.readPolicy(policy.id());
        assertThat(policy.name()).isEqualTo(policyName);
        assertThat(policy.datacenters()).contains(List.of("dc1"));
    }

    @Test
    void testCreateAndReadPolicyByName() {
        var policyName = randomUUIDString();
        PolicyResponse policy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());
        assertThat(policy.name()).isEqualTo(policyName);

        policy = aclClient.readPolicyByName(policy.name());
        assertThat(policy.name()).isEqualTo(policyName);
    }

    @Test
    void testUpdatePolicy() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        String newPolicyName = randomUUIDString();
        aclClient.updatePolicy(createdPolicy.id(), ImmutablePolicy.builder().name(newPolicyName).build());

        PolicyResponse updatedPolicy = aclClient.readPolicy(createdPolicy.id());
        assertThat(updatedPolicy.name()).isEqualTo(newPolicyName);
    }

    @Test
    void testDeletePolicy() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        var oldPolicyCount = aclClient.listPolicies().size();
        aclClient.deletePolicy(createdPolicy.id());
        var newPolicyCount = aclClient.listPolicies().size();

        assertThat(newPolicyCount).isEqualTo(oldPolicyCount - 1);
    }

    @Test
    void testCreateAndReadToken() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        var tokenDescription = randomUUIDString();
        TokenResponse createdToken = aclClient.createToken(ImmutableToken.builder()
                .description(tokenDescription)
                .local(false)
                .addPolicies(ImmutablePolicyLink.builder().id(createdPolicy.id()).build())
                .build());

        TokenResponse readToken = aclClient.readToken(createdToken.accessorId());

        assertThat(readToken.description()).isEqualTo(tokenDescription);

        assertThat(readToken.policies().get(0).name()).contains(policyName);
    }

    @Test
    void testCreateAndCloneTokenWithNewDescription() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        var tokenDescription = randomUUIDString();
        TokenResponse createdToken = aclClient.createToken(
                ImmutableToken.builder()
                        .description(tokenDescription)
                        .local(false)
                        .addPolicies(
                                ImmutablePolicyLink.builder()
                                        .id(createdPolicy.id())
                                        .build()
                        ).build());

        var updatedTokenDescription = randomUUIDString();
        Token updateToken =
                ImmutableToken.builder()
                        .id(createdToken.accessorId())
                        .description(updatedTokenDescription)
                        .build();

        TokenResponse readToken = aclClient.cloneToken(createdToken.accessorId(), updateToken);

        assertThat(readToken.accessorId()).isNotEqualTo(createdToken.accessorId());
        assertThat(readToken.description()).isEqualTo(updatedTokenDescription);
    }

    @Test
    void testCreateAndReadTokenWithCustomIds() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        var tokenId = randomUUIDString();
        var tokenSecretId = randomUUIDString();
        Token token = ImmutableToken.builder()
                .id(tokenId)
                .secretId(tokenSecretId)
                .local(false)
                .addPolicies(
                        ImmutablePolicyLink.builder()
                                .id(createdPolicy.id())
                                .build()
                ).build();
        TokenResponse createdToken = aclClient.createToken(token);

        TokenResponse readToken = aclClient.readToken(createdToken.accessorId());

        assertThat(readToken.accessorId()).isEqualTo(tokenId);
        assertThat(readToken.secretId()).isEqualTo(tokenSecretId);
    }

    @Test
    void testReadSelfToken() {
        TokenResponse selfToken = aclClient.readSelfToken();
        assertThat(selfToken.description()).isEqualTo("Initial Management Token");
    }

    @Test
    void testUpdateToken() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        ImmutableToken newToken = ImmutableToken.builder()
                .description("none")
                .local(false)
                .addPolicies(ImmutablePolicyLink.builder().id(createdPolicy.id()).build())
                .build();
        TokenResponse createdToken = aclClient.createToken(newToken);

        var newDescription = randomUUIDString();
        ImmutableToken tokenUpdates = ImmutableToken.builder()
                .id(createdToken.accessorId())
                .local(false)
                .description(newDescription)
                .build();
        TokenResponse updatedToken = aclClient.updateToken(createdToken.accessorId(), tokenUpdates);
        assertThat(updatedToken.description()).isEqualTo(newDescription);

        TokenResponse readToken = aclClient.readToken(createdToken.accessorId());
        assertThat(readToken.description()).isEqualTo(newDescription);
    }

    @Test
    void testListTokens() {
        assertThat(aclClient.listTokens().stream().anyMatch(p -> Objects.equals(p.description(), "Anonymous Token"))).isTrue();
        assertThat(aclClient.listTokens().stream().anyMatch(p -> Objects.equals(p.description(), "Initial Management Token"))).isTrue();
    }

    @Test
    void testDeleteToken() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());
        TokenResponse createdToken = aclClient.createToken(ImmutableToken.builder().description(randomUUIDString()).local(false).addPolicies(ImmutablePolicyLink.builder().id(createdPolicy.id()).build()).build());

        int oldTokenCount = aclClient.listTokens().size();
        aclClient.deleteToken(createdToken.accessorId());

        int newTokenCount = aclClient.listTokens().size();
        assertThat(newTokenCount).isEqualTo(oldTokenCount - 1);
    }

    @Test
    void testListRoles() {
        var roleName1 = randomUUIDString();
        var roleName2 = randomUUIDString();
        aclClient.createRole(ImmutableRole.builder().name(roleName1).build());
        aclClient.createRole(ImmutableRole.builder().name(roleName2).build());

        assertThat(aclClient.listRoles().stream().anyMatch(p -> Objects.equals(p.name(), roleName1))).isTrue();
        assertThat(aclClient.listRoles().stream().anyMatch(p -> Objects.equals(p.name(), roleName2))).isTrue();
    }

    @Test
    void testCreateAndReadRole() {
        var roleName = randomUUIDString();
        RoleResponse role = aclClient.createRole(ImmutableRole.builder().name(roleName).build());

        RoleResponse roleResponse = aclClient.readRole(role.id());
        assertThat(roleResponse.id()).isEqualTo(role.id());
    }

    @Test
    void testCreateAndReadRoleByName() {
        var roleName = randomUUIDString();
        RoleResponse role = aclClient.createRole(ImmutableRole.builder().name(roleName).build());

        RoleResponse roleResponse = aclClient.readRoleByName(role.name());
        assertThat(roleResponse.name()).isEqualTo(role.name());
    }

    @Test
    void testCreateAndReadRoleWithPolicy() {
        var policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        var roleName = randomUUIDString();
        RoleResponse role = aclClient.createRole(
                ImmutableRole.builder()
                        .name(roleName)
                        .addPolicies(
                                ImmutableRolePolicyLink.builder()
                                        .id(createdPolicy.id())
                                        .build()
                        )
                        .build());

        RoleResponse roleResponse = aclClient.readRole(role.id());
        assertThat(roleResponse.id()).isEqualTo(role.id());
        assertThat(roleResponse.policies()).hasSize(1);
        assertThat(roleResponse.policies().get(0).id()).isPresent();
        assertThat(roleResponse.policies().get(0).id()).contains(createdPolicy.id());
    }

    @Test
    void testUpdateRole() {
        var roleName = randomUUIDString();
        var roleDescription = randomUUIDString();
        RoleResponse role = aclClient.createRole(
                ImmutableRole.builder()
                        .name(roleName)
                        .description(roleDescription)
                        .build());

        RoleResponse roleResponse = aclClient.readRole(role.id());
        assertThat(roleResponse.description()).isEqualTo(roleDescription);

        var roleNewDescription = randomUUIDString();
        RoleResponse updatedRoleResponse = aclClient.updateRole(roleResponse.id(),
                ImmutableRole.builder()
                        .name(roleName)
                        .description(roleNewDescription)
                        .build());

        assertThat(updatedRoleResponse.description()).isEqualTo(roleNewDescription);
    }

    @Test
    void testDeleteRole() {
        var roleName = randomUUIDString();
        RoleResponse role = aclClient.createRole(
                ImmutableRole.builder()
                        .name(roleName)
                        .build());

        RoleResponse roleResponse = aclClient.readRole(role.id());
        assertThat(roleResponse.name()).isEqualTo(roleName);

        String id = roleResponse.id();
        aclClient.deleteRole(id);

        assertThatExceptionOfType(ConsulException.class).isThrownBy(() -> aclClient.readRole(id));
    }

}