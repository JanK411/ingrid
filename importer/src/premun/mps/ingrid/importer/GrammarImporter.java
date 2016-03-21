package premun.mps.ingrid.importer;

import jetbrains.mps.lang.smodel.generator.smodelAdapter.*;
import org.jetbrains.mps.openapi.model.*;
import premun.mps.ingrid.parser.*;
import premun.mps.ingrid.parser.grammar.*;
import premun.mps.ingrid.plugin.import_process.utility.*;

import java.util.*;

public class GrammarImporter {
    private SModel structureModel;
    private GrammarInfo grammar;
    private NamingService namingService;

    public GrammarImporter(SModel structureModel) {
        this.structureModel = structureModel;
        this.namingService = new NamingService(structureModel);
    }

    /**
     * Prepares the target language for import (clears it away).
     */
    private void initializeLanguage() {
        // TODO: Check if language exists and create it if it doesn't (or delete it and create it every time)
        // Delete all nodes
        SModelOperations
            .nodes(this.structureModel, null)
            .stream()
            .forEach(SNodeOperations::deleteNode);
    }

    /**
     * Main method of the import process.
     * @param fileName Name of the ANTLR grammar file to be imported.
     */
    public void importGrammar(String fileName) {
        // TODO: should receive name of the language too
        initializeLanguage();

        this.grammar = GrammarParser.parseFile(fileName);

        this.importTokens();
        this.importRules();
        this.importConceptContents();
    }

    /**
     * Imports all regex lexer rules as constraint data type concepts.
     */
    private void importTokens() {
        this.grammar.rules
            .values()
            .stream()
            .filter(r -> r instanceof RegexRule)
            .forEach(r -> this.importToken((RegexRule) r));
    }

    /**
     * Import all parser rules as either interfaces (split rules) or actual concepts.
     */
    private void importRules() {
        this.grammar.rules
            .values()
            .stream()
            .filter(r -> r instanceof ParserRule)
            .forEach(r -> this.importRule((ParserRule) r));
    }

    /**
     * Imports parser rule children and references.
     */
    private void importConceptContents() {
        this.grammar.rules
            .values()
            .stream()
            .filter(r -> r instanceof ParserRule)
            .forEach(r -> this.importConceptContent((ParserRule) r));
    }

    /**
     * Imports parser rule as an interface or a concept (not their children or editors).
     *
     * For split rules, all alternatives are imported as empty concepts.
     * Example:
     * element:   '<' Name '>' Content '</' Name '>'
     *        |   '<' Name '/>'
     *        ;
     *
     * There will be created one interface and two concepts for this rule that will implements this concept.
     * They will be named element (interface) and element_1 and element_2 (concepts).
     *
     * @param rule Rule to be imported.
     */
    private void importRule(ParserRule rule) {
        // Generate unique name
        rule.name = this.namingService.generateName(rule.name);

        if (rule.alternatives.size() > 1) {
            // Rule with more alternatives - we will create an interface
            // and a child for each alternative that will inherit this interface
            SNode iface = NodeFactory.createInterface(rule.name, "Rules." + rule.name, this.structureModel);
            this.structureModel.addRootNode(iface);

            // For each alternative, there will be a concept
            for (int i = 0; i < rule.alternatives.size(); ++i) {
                // TODO: if only one element is contained inside, we can flatten this rule and delete this intermediate step by advancing to the next step
                String name = this.namingService.generateName(rule.name + "_" + (i + 1));

                // Concrete element, we can create a concept
                SNode concept = NodeFactory.createConcept(name, name, "Rules." + rule.name, rule.equals(this.grammar.rootRule), this.structureModel);

                // Link the parent split rule interface to this rule
                NodeHelper.linkInterfaceToConcept(concept, iface);
                this.structureModel.addRootNode(concept);
            }
        } else {
            // Not a rule that splits into more rules - we create it directly
            SNode concept = NodeFactory.createConcept(rule.name, rule.name, "Rules." + rule.name, rule.equals(this.grammar.rootRule), this.structureModel);
            this.structureModel.addRootNode(concept);
        }
    }

    /**
     * Imports a regex rule as a constraint data type element.
     *
     * @param rule Rule to be imported.
     */
    private void importToken(RegexRule rule) {
        rule.name = this.namingService.generateName(rule.name);
        SNode node = NodeFactory.createConstraintDataType(rule.name, rule.regexp, "Tokens");
        this.structureModel.addRootNode(node);
    }

    private void importConceptContent(ParserRule rule) {
        // TODO: add children elements (ParserRule/RegexRule children)
        // TODO: create editor
        // TODO: add literal rules into editor aspect
        if (rule.alternatives.size() > 1) {
            // Interface - we need to find implementors
            for (int i = 0; i < rule.alternatives.size(); i++) {
                String name = rule.name + "_" + (i + 1);
                SNode concept = this.findConceptByName(name);

                if (concept == null) {
                    // TODO: Log error
                    continue;
                }

                this.importConceptContent(concept, rule.alternatives.get(i));
            }
        } else {
            SNode concept = this.findConceptByRule(rule);

            if (concept == null) {
                // TODO: Log error
            } else {
                this.importConceptContent(concept, rule.alternatives.get(0));
            }
        }
    }

    /**
     * Parses alternative's structure and imports children of a single alternative.
     * @param parent Parent rule, whose alternative we are parsing
     * @param children Alternative's content
     */
    private void importConceptContent(SNode parent, List<RuleReference> children) {
        for (int i = 0; i < children.size(); i++) {
            RuleReference reference = children.get(i);
            Rule childRule = reference.rule;
            SNode child = this.findConceptByRule(childRule);

            String name = childRule.name + "_" + (i + 1);

            if (childRule instanceof ParserRule) {
                NodeHelper.addChildToNode(parent, child, name, reference.quantity);
            } else if (childRule instanceof RegexRule) {
                NodeHelper.addPropertyToNode(parent, name, child);
            }
        }
    }

    /**
     * Finds a concept by it's name.
     *
     * @param name Concept to be matched.
     * @return Concept node belonging to given rule.
     */
    private SNode findConceptByName(String name) {
        for (SNode node : this.structureModel.getRootNodes()) {
            if (name.equals(node.getName())) {
                return node;
            }
        }

        return null;
    }

    /**
     * Finds a concept that was created for given rule.
     *
     * @param rule Rule to be matched.
     * @return Concept node belonging to given rule.
     */
    private SNode findConceptByRule(Rule rule) {
        for (SNode node : this.structureModel.getRootNodes()) {
            if (rule.name.equals(node.getName())) {
                return node;
            }
        }

        return null;
    }
}
