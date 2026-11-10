package com.freecontent.community.content.validation;

import com.freecontent.community.common.error.BusinessException;
import com.freecontent.community.common.error.ErrorCode;
import com.freecontent.community.content.dto.CreateContentRequest;
import com.freecontent.community.content.model.ContentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.List;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ContentDraftValidatorTest {
    private final ContentDraftValidator validator = new ContentDraftValidator();

    @ParameterizedTest @MethodSource("validMarkdown")
    void recognisesOnlyRealMarkdownImages(String markdown, List<String> images) {
        assertDoesNotThrow(() -> validator.validate(input(markdown, images)));
    }

    static Stream<Arguments> validMarkdown() {
        return Stream.of(
                Arguments.of("", List.of()), Arguments.of("asset:a", List.of()),
                Arguments.of("`![demo](asset:a)`", List.of()),
                Arguments.of("```md\n![demo](asset:a)\n```", List.of()),
                Arguments.of("    ![demo](asset:a)\n", List.of()),
                Arguments.of("\\![demo](asset:a)", List.of()),
                Arguments.of("![one](asset:a)", List.of("a")),
                Arguments.of("![one](asset:a) ![again](asset:a)", List.of("a")),
                Arguments.of("![one][photo]\n\n[photo]: asset:a", List.of("a")),
                Arguments.of("![a]\n\n[a]: asset:a", List.of("a")),
                Arguments.of("![one](asset:a \"caption\") ![two](asset:b)", List.of("b", "a")),
                Arguments.of("![one](asset&#58;a)", List.of("a")));
    }

    @ParameterizedTest @MethodSource("invalidMarkdown")
    void rejectsMissingExtraOrUnsupportedImageReferences(String markdown, List<String> images) {
        var failure = assertThrows(BusinessException.class, () -> validator.validate(input(markdown, images)));
        assertEquals(ErrorCode.VALIDATION_ERROR, failure.errorCode());
        assertFalse(failure.violations().isEmpty());
        assertEquals("body", failure.violations().get(0).location());
    }

    static Stream<Arguments> invalidMarkdown() {
        return Stream.of(Arguments.of("![one](asset:a)", List.of()),
                Arguments.of("text", List.of("a")), Arguments.of("![one](asset:a)", List.of("b")),
                Arguments.of("![one](https://example.test/image.png)", List.of()),
                Arguments.of("![one](data:image/png;base64,aaaa)", List.of()),
                Arguments.of("![one](asset:bad.id)", List.of()),
                Arguments.of("![one](asset:)", List.of()),
                Arguments.of("![one](asset:a)", List.of("a", "a")));
    }

    @Test
    void rejectsDuplicateTagsAndVideoOnArticles() {
        var request = input("", List.of()); request.setTagIds(List.of("tag", "tag"));
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class, () -> validator.validate(request)).errorCode());
        request.setTagIds(List.of()); request.setVideoAssetId("video");
        assertEquals(ErrorCode.VALIDATION_ERROR, assertThrows(BusinessException.class, () -> validator.validate(request)).errorCode());
        request.setKind(ContentKind.VIDEO);
        assertDoesNotThrow(() -> validator.validate(request));
    }

    private CreateContentRequest input(String markdown, List<String> images) {
        var input = new CreateContentRequest(); input.setKind(ContentKind.ARTICLE);
        input.setBodyMarkdown(markdown); input.setImageAssetIds(images); return input;
    }
}
