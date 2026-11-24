package com.example.perfume_budget.theme;

import com.example.perfume_budget.repository.CategoryRepository;
import com.example.perfume_budget.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.example.perfume_budget.theme.ThemeTestDocs.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Owner decisions 2026-11-24: caps, enums and error codes of the announcement, story, footer links and social links. */
class ThemeBrandingValidationTest {

    final ThemeValidator validator = new ThemeValidator(MAPPER, mock(ProductRepository.class), mock(CategoryRepository.class), "test");
    final ThemeCanonicalizer canon = new ThemeCanonicalizer(new ThemeCatalog(MAPPER), MAPPER);

    /** A canonical document with every branding member filled in. */
    ObjectNode full() {
        ObjectNode d = (ObjectNode) json("""
                {"schemaVersion":1,"themeId":"morrow","tokens":%s,"assets":{"logo":null,"background":null},
                 "social":{},
                 "pages":{"home":[
                   {"id":"hero","type":"hero","props":{"heading":"Hi","ctaLabel":"Go","ctaHref":"/products","alignment":"left"},"visible":true,"order":0},
                   {"id":"announcement","type":"banner","props":{"message":"Free delivery","linkLabel":"Shop","linkHref":"/products","tone":"primary","dismissible":true},"visible":true,"order":1},
                   {"id":"story","type":"story","props":{"heading":"Our story","body":"Body"},"visible":true,"order":2}],
                  "product":[],
                  "footer":[{"id":"footer","type":"footer","props":{"linkGroups":[{"title":"Help","links":[{"label":"Delivery","href":"/delivery"}]}]},"visible":true,"order":0}]}}
                """.formatted(new ThemeCatalog(MAPPER).tokens("morrow")));
        return d;
    }

    ObjectNode props(ObjectNode d, int homeIndex) {
        return (ObjectNode) d.at("/pages/home/" + homeIndex + "/props");
    }

    List<ThemeFieldError> errors(ObjectNode d) {
        return validator.validateStructure(d);
    }

    void assertOnly(ObjectNode d, String path, String code) {
        assertThat(errors(d)).extracting(ThemeFieldError::path, ThemeFieldError::code).containsExactly(org.assertj.core.groups.Tuple.tuple(path, code));
    }

    @Test
    void fullDocumentIsValid() {
        assertThat(errors(full())).isEmpty();
    }

    // ------------------------------------------------------------------ story

    @Test
    void storyCaps_headingMax80_bodyMax600() {
        ObjectNode d = full();
        props(d, 2).put("heading", "h".repeat(80)).put("body", "b".repeat(600));
        assertThat(errors(d)).isEmpty();
        props(d, 2).put("body", "b".repeat(601));
        assertOnly(d, "pages.home[2].props.body", "TOO_LONG");
        props(d, 2).put("body", "b").put("heading", "h".repeat(81));
        assertOnly(d, "pages.home[2].props.heading", "TOO_LONG");
        props(d, 2).put("heading", "");
        assertOnly(d, "pages.home[2].props.heading", "TOO_SHORT");
        props(d, 2).put("heading", "h").put("body", "");
        assertOnly(d, "pages.home[2].props.body", "TOO_SHORT");
        props(d, 2).remove("body");
        assertOnly(d, "pages.home[2].props.body", "REQUIRED");
    }

    @Test
    void storyBodyIsPlainText_lineBreaksAllowed_markupAndControlsRejected() {
        ObjectNode d = full();
        props(d, 2).put("body", "one\ntwo\r\nthree\tfour\n");
        assertThat(errors(d)).isEmpty();
        for (String bad : new String[]{"<b>x</b>", "a > b", "x\u0000y", "x\u0007y", "x\u007fy"}) {
            props(d, 2).put("body", bad);
            assertOnly(d, "pages.home[2].props.body", "UNSAFE_CONTENT");
        }
        props(d, 2).put("body", 5);
        assertOnly(d, "pages.home[2].props.body", "WRONG_TYPE");
    }

    @Test
    void storyUnknownPropsAndTypeInOtherSlotsAreRejected() {
        ObjectNode d = full();
        props(d, 2).put("align", "left");
        assertOnly(d, "pages.home[2].props.align", "UNKNOWN_PROPERTY");
        ObjectNode e = full();
        ((ArrayNode) e.at("/pages/product")).add(e.at("/pages/home/2").deepCopy());
        assertThat(errors(e)).extracting(ThemeFieldError::code).contains("SECTION_NOT_ALLOWED_IN_SLOT");
    }

    // ------------------------------------------------------------------ announcement

    @Test
    void announcementCaps_messageMax160_linkPairAndValidation() {
        ObjectNode d = full();
        props(d, 1).put("message", "m".repeat(160));
        assertThat(errors(d)).isEmpty();
        props(d, 1).put("message", "m".repeat(161));
        assertOnly(d, "pages.home[1].props.message", "TOO_LONG");
        props(d, 1).put("message", "m").put("linkHref", "javascript:alert(1)");
        assertOnly(d, "pages.home[1].props.linkHref", "URL_NOT_ALLOWED");
        props(d, 1).put("linkHref", "//evil.example/x");
        assertOnly(d, "pages.home[1].props.linkHref", "URL_NOT_ALLOWED");
        props(d, 1).put("linkHref", "https://shop.example/sale");
        assertThat(errors(d)).isEmpty();
        props(d, 1).remove("linkLabel");
        assertThat(errors(d)).isNotEmpty(); // link needs both label and href
        props(d, 1).remove("linkHref");
        assertThat(errors(d)).isEmpty(); // message alone is fine
        props(d, 1).put("message", "<script>");
        assertOnly(d, "pages.home[1].props.message", "UNSAFE_CONTENT");
    }

    // ------------------------------------------------------------------ footer link columns

    @Test
    void footerCaps_3groups_6links_30charLabels() {
        ObjectNode d = full();
        ArrayNode groups = (ArrayNode) d.at("/pages/footer/0/props/linkGroups");
        ObjectNode g = (ObjectNode) groups.get(0);
        ArrayNode links = (ArrayNode) g.get("links");
        for (int i = 0; i < 5; i++) {
            links.add(json("{\"label\":\"L" + i + "\",\"href\":\"/p" + i + "\"}"));
        }
        assertThat(links.size()).isEqualTo(6);
        assertThat(errors(d)).isEmpty();
        links.add(json("{\"label\":\"seven\",\"href\":\"/p\"}"));
        assertOnly(d, "pages.footer[0].props.linkGroups[0].links", "TOO_LONG");
        links.remove(6);
        groups.add(g.deepCopy());
        groups.add(g.deepCopy());
        assertThat(errors(d)).isEmpty();
        groups.add(g.deepCopy());
        assertOnly(d, "pages.footer[0].props.linkGroups", "TOO_LONG");
        groups.remove(3);
        g.put("title", "t".repeat(31));
        assertThat(errors(d)).extracting(ThemeFieldError::path).contains("pages.footer[0].props.linkGroups[0].title");
        g.put("title", "t".repeat(30));
        ((ObjectNode) links.get(0)).put("href", "javascript:alert(1)");
        assertOnly(d, "pages.footer[0].props.linkGroups[0].links[0].href", "URL_NOT_ALLOWED");
    }

    // ------------------------------------------------------------------ social

    static final Map<String, List<String>> OK = Map.of(
            "instagram", List.of("https://instagram.com/shop", "https://www.instagram.com/shop/", "https://www.instagram.com/shop?hl=en"),
            "facebook", List.of("https://facebook.com/shop", "https://www.facebook.com/shop", "https://m.facebook.com/shop"),
            "x", List.of("https://x.com/shop", "https://twitter.com/shop", "https://www.twitter.com/shop"),
            "tiktok", List.of("https://tiktok.com/@shop", "https://www.tiktok.com/@shop"),
            "youtube", List.of("https://youtube.com/@shop", "https://www.youtube.com/watch?v=abc", "https://m.youtube.com/c/shop", "https://youtu.be/abc"),
            "whatsapp", List.of("https://wa.me/233200000000", "https://api.whatsapp.com/send?phone=1", "https://chat.whatsapp.com/abc", "https://whatsapp.com/channel/x"),
            "linkedin", List.of("https://linkedin.com/company/shop", "https://www.linkedin.com/company/shop"),
            "pinterest", List.of("https://pinterest.com/shop", "https://www.pinterest.com/shop"));

    @Test
    void everyPlatformAcceptsItsOwnHosts() {
        OK.forEach((platform, urls) -> urls.forEach(u -> {
            ObjectNode d = full();
            d.with("social").put(platform, u);
            assertThat(errors(d)).as("%s %s", platform, u).isEmpty();
        }));
        ObjectNode all = full();
        OK.forEach((p, urls) -> all.with("social").put(p, urls.get(0)));
        assertThat(errors(all)).isEmpty();
        assertThat(OK.keySet()).containsExactlyInAnyOrder("instagram", "facebook", "x", "tiktok", "youtube", "whatsapp", "linkedin", "pinterest");
    }

    @Test
    void aPlatformRejectsOtherHostsAndEveryUnsafeShape() {
        String[] unsafe = {"http://instagram.com/x", "https://instagram.com.evil.example/x", "https://evil.example/instagram.com",
                "https://user@instagram.com/x", "https://user:pw@instagram.com/x", "https://instagram.com:8443/x", "https://instagram.com@evil.example/",
                "javascript:alert(1)", "data:text/html,x", "//instagram.com/x", "instagram.com/x", "https://instagram.com/a b", "https://instagram.com/<x>",
                "https://instagram.com/x\n", "https://instagram.com/\"x", "https://", "https://evil-instagram.com/x", "ftp://instagram.com/x"};
        for (String u : unsafe) {
            ObjectNode d = full();
            d.with("social").put("instagram", u);
            assertOnly(d, "social.instagram", "URL_NOT_ALLOWED");
        }
        // each platform's URL is refused on every other platform's key
        OK.forEach((platform, urls) -> OK.forEach((other, otherUrls) -> {
            if (!platform.equals(other) && !(platform.equals("x") && other.equals("x"))) {
                ObjectNode d = full();
                d.with("social").put(platform, otherUrls.get(0));
                assertThat(errors(d)).as("%s key with %s url", platform, other).extracting(ThemeFieldError::code).containsExactly("URL_NOT_ALLOWED");
            }
        }));
    }

    @Test
    void socialLengthCap256_unknownPlatformAndWrongTypes() {
        ObjectNode d = full();
        d.with("social").put("pinterest", "https://pinterest.com/" + "p".repeat(234));
        assertThat(errors(d)).isEmpty();
        d.with("social").put("pinterest", "https://pinterest.com/" + "p".repeat(235));
        assertOnly(d, "social.pinterest", "TOO_LONG");
        ObjectNode u = full();
        u.with("social").put("myspace", "https://myspace.com/x");
        assertOnly(u, "social.myspace", "UNKNOWN_PROPERTY");
        for (JsonNode bad : new JsonNode[]{MAPPER.getNodeFactory().numberNode(1), MAPPER.createArrayNode(), MAPPER.createObjectNode(), MAPPER.getNodeFactory().booleanNode(true)}) {
            ObjectNode w = full();
            w.with("social").set("instagram", bad);
            assertOnly(w, "social.instagram", "WRONG_TYPE");
        }
        ObjectNode notObject = full();
        notObject.put("social", "x");
        assertOnly(notObject, "social", "WRONG_TYPE");
    }

    @Test
    void canonicalizerDropsNullSocialEntries_soNullIsAlwaysValid() {
        ObjectNode in = full();
        in.with("social").putNull("instagram").put("facebook", "https://facebook.com/x");
        ObjectNode out = canon.canonicalize(in, canon.resolveStored(defaults(), defaults())).document();
        assertThat(out.at("/social").toString()).isEqualTo("{\"facebook\":\"https://facebook.com/x\"}");
        assertThat(errors(out)).isEmpty();
    }
}
