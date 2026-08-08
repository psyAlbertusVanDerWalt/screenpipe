package za.co.albertusvdw.graphiti.ingester.core.data.ontology;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OntologyTest {

    @Test
    @DisplayName("the ontology is exactly the deployed 13 entity and 18 edge types")
    void matchesTheDeployedOntology() {
        // A count check is worth having: the failure this guards against is the deployed
        // ontology growing while this enum silently does not, which shows up as valid
        // extractions being rejected rather than as anything obviously broken.
        assertThat(EntityType.values()).hasSize(13);
        assertThat(EdgeType.values()).hasSize(18);
    }

    @Test
    @DisplayName("out-of-enum types invented by the model are rejected")
    void rejectsInventedTypes() {
        assertThat(EntityType.fromWireName("Deliverable")).isEmpty();
        assertThat(EntityType.fromWireName("Requirement")).isEmpty();
        assertThat(EdgeType.fromWireName("RELATES_TO")).isEmpty();
    }

    @Test
    @DisplayName("casing differences resolve, since models return person for Person")
    void resolvesCaseInsensitively() {
        assertThat(EntityType.fromWireName("person")).contains(EntityType.PERSON);
        assertThat(EntityType.fromWireName("  PERSON  ")).contains(EntityType.PERSON);
        assertThat(EdgeType.fromWireName("works_on")).contains(EdgeType.WORKS_ON);
    }

    @Test
    @DisplayName("null and blank names are rejected rather than resolving to anything")
    void rejectsMissingNames() {
        assertThat(EntityType.fromWireName(null)).isEmpty();
        assertThat(EntityType.fromWireName("   ")).isEmpty();
    }

    @Test
    @DisplayName("wire names keep the exact spelling the deployed ontology uses")
    void keepsExactWireSpelling() {
        assertThat(EntityType.PERSON.wireName()).isEqualTo("Person");
        assertThat(EntityType.ORGANIZATION.wireName()).isEqualTo("Organization");
        assertThat(EdgeType.COLLABORATES_WITH.wireName()).isEqualTo("COLLABORATES_WITH");
    }
}
