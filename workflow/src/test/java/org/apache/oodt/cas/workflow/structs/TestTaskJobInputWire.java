package org.apache.oodt.cas.workflow.structs;

import junit.framework.TestCase;
import org.apache.oodt.cas.metadata.Metadata;
import org.apache.oodt.cas.resource.structs.AvroTypeFactory;
import org.apache.oodt.cas.resource.structs.avrotypes.AvroJobInput;

/**
 * A {@link TaskJobInput} has to arrive at the batch stub carrying the metadata
 * it left with, or every condition that reads the shared context holds forever.
 */
public class TestTaskJobInputWire extends TestCase {

  public void testMetadataSurvivesTheWire() {
    Metadata met = new Metadata();
    met.addMetadata("Filename", "chunk-00376.json");
    met.addMetadata("ProductType", "EmploymentStringChunk");

    TaskJobInput in = new TaskJobInput();
    in.setDynMetadata(met);
    in.setWorkflowTaskInstanceClassName("some.Task");

    AvroJobInput wire = AvroTypeFactory.getAvroJobInput(in);
    TaskJobInput out = (TaskJobInput) AvroTypeFactory.getJobInput(wire);

    assertNotNull(out.getDynMetadata());
    assertEquals("chunk-00376.json", out.getDynMetadata().getMetadata("Filename"));
    assertEquals("EmploymentStringChunk",
        out.getDynMetadata().getMetadata("ProductType"));
  }
}
