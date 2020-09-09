package org.pharmgkb.pharmcat.haplotype;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import org.junit.jupiter.api.Test;
import org.pharmgkb.common.util.PathUtils;
import org.pharmgkb.pharmcat.definition.model.NamedAllele;
import org.pharmgkb.pharmcat.haplotype.model.DiplotypeMatch;
import org.pharmgkb.pharmcat.haplotype.model.GeneCall;
import org.pharmgkb.pharmcat.haplotype.model.Result;
import org.pharmgkb.pharmcat.reporter.model.result.Diplotype;
import org.pharmgkb.pharmcat.util.DataManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.*;


/**
 * JUnit test for {@link NamedAlleleMatcher}.
 *
 * @author Mark Woon
 */
class NamedAlleleMatcherTest {


  /**
   * Helper method for running {@link NamedAlleleMatcher} using:
   * <ul>
   *   <li>{@code assumeReference} = true</li>
   *   <li>{@code topCandidateOnly} = false</li>
   *   <li>{@code showUnmatched} = true</li>
   *   <li>{@code withExemptions} = true</li>
   * </ul>
   *
   */
  static Result testMatchNamedAlleles(@Nonnull Path tsvFile, @Nonnull Path vcfFile) throws Exception {
    return testMatchNamedAlleles(tsvFile, vcfFile, true, false, true, true);
  }

  /**
   * Helper method for running {@link NamedAlleleMatcher}.
   * This is used by the more specific gene tests.
   */
  static Result testMatchNamedAlleles(@Nonnull Path definitionFile, @Nonnull Path vcfFile,
      boolean assumeReference, boolean topCandidateOnly, boolean showUnmatched, boolean withExemptions) throws Exception {

    DefinitionReader definitionReader = new DefinitionReader();
    definitionReader.read(definitionFile);
    if (withExemptions) {
      definitionReader.readExemptions(DataManager.DEFAULT_DEFINITION_DIR.resolve(DataManager.EXEMPTIONS_JSON_FILE_NAME));
    }

    NamedAlleleMatcher namedAlleleMatcher = new NamedAlleleMatcher(definitionReader, assumeReference, topCandidateOnly);
    Result result = namedAlleleMatcher.call(vcfFile);

    // print
    new ResultSerializer()
        .alwaysShowUnmatchedHaplotypes(showUnmatched)
        .toHtml(result, vcfFile.getParent().resolve(PathUtils.getBaseFilename(vcfFile) + ".html"))
        .toJson(result, vcfFile.getParent().resolve(PathUtils.getBaseFilename(vcfFile) + ".json"));

    return result;
  }


  /**
   * Checks that the list of diplotype matches are what we expect.
   *
   * @param expectedPairs the set of expected diplotypes in "*1/*2" format
   * @param result the {@link NamedAlleleMatcher} results
   */
  static void assertDiplotypePairs(@Nonnull List<String> expectedPairs,
      @Nonnull Result result) {

    Preconditions.checkNotNull(expectedPairs);
    Preconditions.checkNotNull(result);

    List<String> pairs = new ArrayList<>();
    StringBuilder builder = new StringBuilder();
    for (DiplotypeMatch dm : result.getGeneCalls().get(0).getDiplotypes()) {
      pairs.add(dm.getName());
      if (builder.length() > 0) {
        builder.append(", ");
      }
      builder.append(dm.getName())
          .append(" (")
          .append(dm.getScore())
          .append(")");
    }

    if (expectedPairs.size() != pairs.size() || !expectedPairs.equals(pairs)) {
      System.out.println("Expected: [" + Joiner.on(", ").join(expectedPairs) + "]");
      System.out.println("Got:      " + pairs);
      System.out.println("Scores:   " + builder.toString());
      fail("Did not get expected matches");
    }
  }

  static void assertPhasedOutput(@Nonnull String expected, @Nonnull Result result) {
    List<String> pairs = new ArrayList<>();
    GeneCall geneCall = result.getGeneCalls().get(0);

    assertTrue(geneCall.isPhased(), "Provided sample is not phased so this test is improper");

    for (DiplotypeMatch dm : result.getGeneCalls().get(0).getDiplotypes()) {
      pairs.add(dm.getName());
    }

    String testResult = pairs.stream().reduce(Diplotype.phasedReducer).orElseThrow(RuntimeException::new);

    assertEquals(expected, testResult, "Phased output wasn't expected");
  }


  @Test
  void testCall() throws Exception {

    Path vcfFile  = PathUtils.getPathToResource("org/pharmgkb/pharmcat/haplotype/haplotyper.vcf");
    Path jsonFile = PathUtils.getPathToResource("org/pharmgkb/pharmcat/haplotype/haplotyper.json");

    DefinitionReader definitionReader = new DefinitionReader();
    definitionReader.read(jsonFile);

    NamedAlleleMatcher namedAlleleMatcher = new NamedAlleleMatcher(definitionReader);
    Result result = namedAlleleMatcher.call(vcfFile);
    Set<DiplotypeMatch> pairs = result.getGeneCalls().get(0).getDiplotypes();
    assertNotNull(pairs);
    assertEquals(1, pairs.size());
    assertEquals("*1/*2", pairs.iterator().next().getName());
  }


  /**
   * This breaks down the main code path that {@link #testCall()} runs to simplify testing smaller chunks at a time.
   */
  @Test
  void testCallDiplotypePath() throws Exception {

    Path vcfFile  = PathUtils.getPathToResource("org/pharmgkb/pharmcat/haplotype/haplotyper.vcf");
    Path jsonFile = PathUtils.getPathToResource("org/pharmgkb/pharmcat/haplotype/haplotyper.json");
    Set<String> permutations = Sets.newHashSet(
        "1:C;2:del;3:C;",
        "1:T;2:insA;3:delC;"
    );
    String gene = "CYP3A5";

    DefinitionReader definitionReader = new DefinitionReader();
    definitionReader.read(jsonFile);

    NamedAlleleMatcher namedAlleleMatcher = new NamedAlleleMatcher(definitionReader);
    VcfReader vcfReader = namedAlleleMatcher.buildVcfReader(vcfFile);

    // grab SampleAlleles for all positions related to current gene
    MatchData data = new MatchData(vcfReader.getAlleleMap(), definitionReader.getPositions(gene), null, null);
    assertEquals(3, data.getNumSampleAlleles());
    assertEquals(0, data.getMissingPositions().size());
    // handle missing positions of interest in sample
    data.marshallHaplotypes(definitionReader.getHaplotypes(gene));
    assertEquals(3, data.getPositions().length);
    assertEquals(2, data.getHaplotypes().size());

    // get all permutations of sample at positions of interest
    data.generateSamplePermutations();
    assertThat(data.getPermutations(), equalTo(permutations));

    for (NamedAllele hap : data.getHaplotypes()) {
      System.out.println(hap.getName() + ": " + hap.getPermutations().pattern());
    }

    List<DiplotypeMatch> pairs = new DiplotypeMatcher(data).compute();
    assertNotNull(pairs);
    assertEquals(1, pairs.size());
    assertEquals("*1/*2", pairs.get(0).getName());
  }
}
