/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.app.rest.model.hateoas;

import org.dspace.app.rest.model.FacetConfigurationRest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * This class' purpose is to test the FacetConfigurationRest class
 */
public class FacetConfigurationResourceTest {
    private FacetConfigurationRest facetConfigurationRest;

    @BeforeEach
    public void setUp() throws Exception {
        facetConfigurationRest = new FacetConfigurationRest();
    }

    @Test
    public void testConstructorWithNullThrowsException() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            FacetConfigurationResource facetConfigurationResource = new FacetConfigurationResource(null);
        });
    }

    @Test
    public void testConstructorAndGetterWithProperDataAndObjectNotNull() throws Exception {
        FacetConfigurationResource facetConfigurationResource = new FacetConfigurationResource(facetConfigurationRest);
        assertNotNull(facetConfigurationResource);
        assertNotNull(facetConfigurationResource.getContent());
    }

    @Test
    public void testConstructorAndGetterWithProperDataAndProperDataReturned() throws Exception {
        FacetConfigurationResource facetConfigurationResource = new FacetConfigurationResource(facetConfigurationRest);
        assertEquals(facetConfigurationRest, facetConfigurationResource.getContent());
    }

}
