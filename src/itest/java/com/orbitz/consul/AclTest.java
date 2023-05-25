package com.orbitz.consul;

import static com.orbitz.consul.TestUtils.randomUUIDString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsNot.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class AclTest {

    public static GenericContainer<?> consulContainerAcl;
    static {
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

    @BeforeClass
    public static void beforeClass() {
        client = Consul.builder()
                .withHostAndPort(aclClientHostAndPort)
                .withAclToken("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
                .withReadTimeoutMillis(Duration.ofSeconds(2).toMillis())
                .build();
    }

    @Test
    public void listPolicies() {
        AclClient aclClient = client.aclClient();
        assertTrue(aclClient.listPolicies().stream().anyMatch(p -> Objects.equals(p.name(), "global-management")));
    }

    @Test
    public void testCreateAndReadPolicy() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse policy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());
        assertThat(policy.name(), is(policyName));
        assertThat(policy.datacenters(), is(Optional.empty()));

        policy = aclClient.readPolicy(policy.id());
        assertThat(policy.name(), is(policyName));
        assertThat(policy.datacenters(), is(Optional.empty()));
    }

    @Test
    public void testCreateAndReadPolicy_WithDatacenters() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        ImmutablePolicy newPolicy = ImmutablePolicy.builder().name(policyName).datacenters(List.of("dc1")).build();
        PolicyResponse policy = aclClient.createPolicy(newPolicy);
        assertThat(policy.name(), is(policyName));
        assertThat(policy.datacenters(), is(Optional.of(List.of("dc1"))));

        policy = aclClient.readPolicy(policy.id());
        assertThat(policy.name(), is(policyName));
        assertThat(policy.datacenters(), is(Optional.of(List.of("dc1"))));
    }

    @Test
    public void testCreateAndReadPolicyByName() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse policy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());
        assertThat(policy.name(), is(policyName));

        policy = aclClient.readPolicyByName(policy.name());
        assertThat(policy.name(), is(policyName));
    }

    @Test
    public void testUpdatePolicy() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        String newPolicyName = randomUUIDString();
        aclClient.updatePolicy(createdPolicy.id(), ImmutablePolicy.builder().name(newPolicyName).build());

        PolicyResponse updatedPolicy = aclClient.readPolicy(createdPolicy.id());
        assertThat(updatedPolicy.name(), is(newPolicyName));
    }

    @Test
    public void testDeletePolicy() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        int oldPolicyCount = aclClient.listPolicies().size();
        aclClient.deletePolicy(createdPolicy.id());
        int newPolicyCount = aclClient.listPolicies().size();

        assertThat(newPolicyCount, is(oldPolicyCount - 1));
    }

    @Test
    public void testCreateAndReadToken() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        String tokenDescription = randomUUIDString();
        TokenResponse createdToken = aclClient.createToken(ImmutableToken.builder().description(tokenDescription).local(false).addPolicies(ImmutablePolicyLink.builder().id(createdPolicy.id()).build()).build());

        TokenResponse readToken = aclClient.readToken(createdToken.accessorId());

        assertThat(readToken.description(), is(tokenDescription));
        assertThat(readToken.policies().get(0).name().get(), is(policyName));
    }

    @Test
    public void testCreateAndCloneTokenWithNewDescription() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        String tokenDescription = randomUUIDString();
        TokenResponse createdToken = aclClient.createToken(
                ImmutableToken.builder()
                        .description(tokenDescription)
                        .local(false)
                        .addPolicies(
                                ImmutablePolicyLink.builder()
                                        .id(createdPolicy.id())
                                        .build()
                        ).build());

        String updatedTokenDescription = randomUUIDString();
        Token updateToken =
                ImmutableToken.builder()
                        .id(createdToken.accessorId())
                        .description(updatedTokenDescription)
                        .build();

        TokenResponse readToken = aclClient.cloneToken(createdToken.accessorId(), updateToken);

        assertThat(readToken.accessorId(), not(createdToken.accessorId()));
        assertThat(readToken.description(), is(updatedTokenDescription));
    }

    @Test
    public void testCreateAndReadTokenWithCustomIds() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        String tokenId = randomUUIDString();
        String tokenSecretId = randomUUIDString();
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

        assertThat(readToken.accessorId(), is(tokenId));
        assertThat(readToken.secretId(), is(tokenSecretId));
    }

    @Test
    public void testReadSelfToken() {
        AclClient aclClient = client.aclClient();

        TokenResponse selfToken = aclClient.readSelfToken();
        assertThat(selfToken.description(), is("Initial Management Token"));
    }

    @Test
    public void testUpdateToken() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        ImmutableToken newToken = ImmutableToken.builder()
                .description("none")
                .local(false)
                .addPolicies(ImmutablePolicyLink.builder().id(createdPolicy.id()).build())
                .build();
        TokenResponse createdToken = aclClient.createToken(newToken);

        String newDescription = randomUUIDString();
        ImmutableToken tokenUpdates = ImmutableToken.builder()
                .id(createdToken.accessorId())
                .local(false)
                .description(newDescription)
                .build();
        TokenResponse updatedToken = aclClient.updateToken(createdToken.accessorId(), tokenUpdates);
        assertThat(updatedToken.description(), is(newDescription));

        TokenResponse readToken = aclClient.readToken(createdToken.accessorId());
        assertThat(readToken.description(), is(newDescription));
    }

    @Test
    public void testListTokens() {
        AclClient aclClient = client.aclClient();

        assertTrue(aclClient.listTokens().stream().anyMatch(p -> Objects.equals(p.description(), "Anonymous Token")));
        assertTrue(aclClient.listTokens().stream().anyMatch(p -> Objects.equals(p.description(), "Initial Management Token")));
    }

    @Test
    public void testDeleteToken() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());
        TokenResponse createdToken = aclClient.createToken(ImmutableToken.builder().description(randomUUIDString()).local(false).addPolicies(ImmutablePolicyLink.builder().id(createdPolicy.id()).build()).build());

        int oldTokenCount = aclClient.listTokens().size();
        aclClient.deleteToken(createdToken.accessorId());

        int newTokenCount = aclClient.listTokens().size();
        assertThat(newTokenCount, is(oldTokenCount - 1));
    }

    @Test
    public void testListRoles() {
        AclClient aclClient = client.aclClient();

        String roleName1 = randomUUIDString();
        String roleName2 = randomUUIDString();
        aclClient.createRole(ImmutableRole.builder().name(roleName1).build());
        aclClient.createRole(ImmutableRole.builder().name(roleName2).build());

        assertTrue(aclClient.listRoles().stream().anyMatch(p -> Objects.equals(p.name(), roleName1)));
        assertTrue(aclClient.listRoles().stream().anyMatch(p -> Objects.equals(p.name(), roleName2)));
    }

    @Test
    public void testCreateAndReadRole() {
        AclClient aclClient = client.aclClient();

        String roleName = randomUUIDString();
        RoleResponse role = aclClient.createRole(ImmutableRole.builder().name(roleName).build());

        RoleResponse roleResponse = aclClient.readRole(role.id());
        assertEquals(role.id(), roleResponse.id());
    }

    @Test
    public void testCreateAndReadRoleByName() {
        AclClient aclClient = client.aclClient();

        String roleName = randomUUIDString();
        RoleResponse role = aclClient.createRole(ImmutableRole.builder().name(roleName).build());

        RoleResponse roleResponse = aclClient.readRoleByName(role.name());
        assertEquals(role.name(), roleResponse.name());
    }

    @Test
    public void testCreateAndReadRoleWithPolicy() {
        AclClient aclClient = client.aclClient();

        String policyName = randomUUIDString();
        PolicyResponse createdPolicy = aclClient.createPolicy(ImmutablePolicy.builder().name(policyName).build());

        String roleName = randomUUIDString();
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
        assertEquals(role.id(), roleResponse.id());
        assertEquals(1, roleResponse.policies().size());
        assertTrue(roleResponse.policies().get(0).id().isPresent());
        assertEquals(createdPolicy.id(), roleResponse.policies().get(0).id().get());
    }

    @Test
    public void testUpdateRole() {
        AclClient aclClient = client.aclClient();

        String roleName = randomUUIDString();
        String roleDescription = randomUUIDString();
        RoleResponse role = aclClient.createRole(
                ImmutableRole.builder()
                        .name(roleName)
                        .description(roleDescription)
                        .build());

        RoleResponse roleResponse = aclClient.readRole(role.id());
        assertEquals(roleDescription, roleResponse.description());

        String roleNewDescription = randomUUIDString();
        RoleResponse updatedRoleResponse = aclClient.updateRole(roleResponse.id(),
                ImmutableRole.builder()
                        .name(roleName)
                        .description(roleNewDescription)
                        .build());

        assertEquals(roleNewDescription, updatedRoleResponse.description());
    }

    @Test
    public void testDeleteRole() {
        AclClient aclClient = client.aclClient();

        String roleName = randomUUIDString();
        RoleResponse role = aclClient.createRole(
                ImmutableRole.builder()
                        .name(roleName)
                        .build());

        RoleResponse roleResponse = aclClient.readRole(role.id());
        assertEquals(roleName, roleResponse.name());

        String id = roleResponse.id();
        aclClient.deleteRole(id);

        assertThrows(ConsulException.class, () -> aclClient.readRole(id));
    }

}
