package com.agentic.orchestration.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CodebaseIndexTest {

    @Test
    void commentsAreNotDependencies() {
        String source = """
                /** Delegates to {@link ShortLinkService#create}. */
                record CreateLinkCommand(String url) { // see RedirectService
                    static final String DOCS = "https://example.com/ShortLinkService"; String keep = ClickContext.X;
                }""";
        String code = CodebaseIndex.withoutComments(source);

        assertThat(code).doesNotContain("{@link ShortLinkService").doesNotContain("RedirectService")
                .as("a URL inside a string is not a comment").contains("https://example.com/ShortLinkService")
                .contains("ClickContext.X");
    }

    @Test
    void javadocLinksDoNotCreateEdgesInThisRepository() {
        CodebaseIndex index = CodebaseIndex.scan(Path.of("."), "com.agentic");
        // CreateLinkCommand only mentions ShortLinkService in its Javadoc; ShortLinkService only
        // mentions RedirectService in its Javadoc. Neither is a real dependency.
        assertThat(index.dependenciesOf("com.agentic.shortener.service.CreateLinkCommand")).isEmpty();
        assertThat(index.dependenciesOf("com.agentic.shortener.service.ShortLinkService"))
                .doesNotContain("com.agentic.shortener.service.RedirectService")
                .contains("com.agentic.shortener.service.RedirectCache");
    }
}
