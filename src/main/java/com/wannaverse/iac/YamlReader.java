package com.wannaverse.iac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

import java.util.regex.Pattern;

/**
 * Reads YAML text into a Jackson tree.
 *
 * <p>SnakeYAML does the reading because it understands anchors, aliases and merge keys ({@code <<:
 * *defaults}), which Jackson's YAML parser does not. Plain values follow YAML 1.2, as people
 * expect: only true and false are booleans ({@code restart: no} stays the text "no"), {@code
 * 8080:80} is text rather than a base-60 number, and dates stay text.
 */
final class YamlReader {

    private YamlReader() {}

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private static final Pattern CORE_BOOL =
            Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$");
    private static final Pattern CORE_INT =
            Pattern.compile("^(?:[-+]?(?:0|[1-9][0-9]*)|0x[0-9a-fA-F]+)$");
    private static final Pattern CORE_FLOAT =
            Pattern.compile(
                    "^(?:[-+]?(?:\\.[0-9]+|[0-9]+\\.[0-9]*)(?:[eE][-+]?[0-9]+)?"
                            + "|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$");
    private static final Pattern CORE_NULL = Pattern.compile("^(?:~|null|Null|NULL|)$");
    private static final Pattern CORE_MERGE = Pattern.compile("^<<$");

    /**
     * YAML 1.2 core schema scalars, plus merge keys. (The patterns are named CORE_* because
     * Resolver has static fields called BOOL, INT... that would shadow them in here.)
     */
    private static final class CoreResolver extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, CORE_BOOL, "tTfF");
            addImplicitResolver(Tag.INT, CORE_INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, CORE_FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, CORE_MERGE, "<");
            addImplicitResolver(Tag.NULL, CORE_NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, CORE_NULL, null);
        }
    }

    static JsonNode read(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(200);
        options.setCodePointLimit(4 * 1024 * 1024);
        DumperOptions dump = new DumperOptions();
        Yaml yaml =
                new Yaml(
                        new SafeConstructor(options),
                        new Representer(dump),
                        dump,
                        options,
                        new CoreResolver());
        Object loaded;
        try {
            loaded = yaml.load(text);
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark() != null ? e.getProblemMark() : e.getContextMark();
            String where =
                    mark == null
                            ? ""
                            : "Line "
                                    + (mark.getLine() + 1)
                                    + ", column "
                                    + (mark.getColumn() + 1)
                                    + ": ";
            String what = e.getProblem() != null ? e.getProblem() : e.getMessage();
            throw new IacException(where + "the file is not valid YAML (" + what + ")");
        } catch (YAMLException e) {
            throw new IacException("The file is not valid YAML (" + e.getMessage() + ")");
        }
        // A plain mapper: IacJson's leave out empty values, which would drop entries written as {}.
        return PLAIN.valueToTree(loaded);
    }
}
