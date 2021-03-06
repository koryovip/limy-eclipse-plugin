/*
 * Created 2007/08/30
 * Copyright (C) 2003-2009  Naoki Iwami (naoki@limy.org)
 *
 * This file is part of Limy Eclipse Plugin.
 *
 * Limy Eclipse Plugin is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Limy Eclipse Plugin is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Limy Eclipse Plugin.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.limy.eclipse.qalab.umlgraph;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.limy.eclipse.qalab.umlgraph.javadoc.ClassDoc;
import org.limy.eclipse.qalab.umlgraph.javadoc.LanguageVersion;
import org.limy.eclipse.qalab.umlgraph.javadoc.PackageDoc;
import org.limy.eclipse.qalab.umlgraph.javadoc.RootDoc;
import org.limy.eclipse.qalab.umlgraph.javadoc.Standard;


/**
 * Chaining doclet that runs the standart Javadoc doclet first, and on success,
 * runs the generation of dot files by UMLGraph
 * @author wolf
 * 
 * @depend - - - WrappedClassDoc
 * @depend - - - WrappedRootDoc
 */
public class UmlGraphDoc {
    /**
     * Option check, forwards options to the standard doclet, if that one refuses them,
     * they are sent to UmlGraph
     */
    public static int optionLength(String option) {
        int result = Standard.optionLength(option);
        if (result != 0)
            return result;
        else
            return UmlGraph.optionLength(option);
    }

    /**
     * Standard doclet entry point
     * @param root
     * @return
     */
    public static boolean start(RootDoc root) {
        root.printNotice("UmlGraphDoc version " + Version.VERSION +  ", running the standard doclet");
        Standard.start(root);
        root.printNotice("UmlGraphDoc version " + Version.VERSION + ", altering javadocs");
        try {
            String outputFolder = findOutputPath(root.options());

            Options opt = new Options();
            opt.setOptions(root.options());
            // in javadoc enumerations are always printed
            opt.showEnumerations = true;
            opt.relativeLinksForSourcePackages = true;
            // enable strict matching for hide expressions
            opt.strictMatching = true;
//            root.printNotice(opt.toString());

            root = new WrappedRootDoc(root);
            generatePackageDiagrams(root, opt, outputFolder);
            generateContextDiagrams(root, opt, outputFolder);
        } catch(Throwable t) {
            root.printWarning("Error!");
            root.printWarning(t.toString());
            t.printStackTrace();
            return false;
        }
        return true;
    }

    /**
     * Standand doclet entry
     * @return
     */
    public static LanguageVersion languageVersion() {
        return Standard.languageVersion();
    }

    /**
     * Generates the package diagrams for all of the packages that contain classes among those 
     * returned by RootDoc.class() 
     */
    private static void generatePackageDiagrams(RootDoc root, Options opt, String outputFolder)
            throws IOException {
        Set<String> packages = new HashSet<String>();
        for (ClassDoc classDoc : root.classes()) {
            PackageDoc packageDoc = classDoc.containingPackage();
            if(!packages.contains(packageDoc.name())) {
                packages.add(packageDoc.name());
                OptionProvider view = new PackageView(outputFolder, packageDoc, root, opt);
                UmlGraph.buildGraph(root, view, packageDoc);
                runGraphviz(outputFolder, packageDoc.name(), packageDoc.name(), root);
                alterHtmlDocs(outputFolder, packageDoc.name(), packageDoc.name(),
                        "package-summary.html", Pattern.compile("</H2>"), root);
            }
        }
    }

    /**
     * Generates the context diagram for a single class
     */
    private static void generateContextDiagrams(RootDoc root, Options opt, String outputFolder)
            throws IOException {
        ContextView view = null;
        for (ClassDoc classDoc : root.classes()) {
            if(view == null)
                view = new ContextView(outputFolder, classDoc, root, opt);
            else
                view.setContextCenter(classDoc);
            UmlGraph.buildGraph(root, view, classDoc);
            runGraphviz(outputFolder, classDoc.containingPackage().name(), classDoc.name(), root);
            alterHtmlDocs(outputFolder, classDoc.containingPackage().name(), classDoc.name(),
                    classDoc.name() + ".html", Pattern.compile("(Class|Interface|Enum) " + classDoc.name() + ".*") , root);
        }
    }

    /**
     * Runs Graphviz dot building both a diagram (in png format) and a client side map for it.
     * <p>
     * At the moment, it assumes dot.exe is in the classpahth
     */
    private static void runGraphviz(String outputFolder, String packageName, String name, RootDoc root) {
        File dotFile = new File(outputFolder, packageName.replace(".", "/") + "/" + name + ".dot");
        File pngFile = new File(outputFolder, packageName.replace(".", "/") + "/" + name + ".png");
        File mapFile = new File(outputFolder, packageName.replace(".", "/") + "/" + name + ".map");

        try {
            Process p = Runtime.getRuntime().exec(new String [] {
                "dot",
                "-Tcmapx",
                "-o",
                mapFile.getAbsolutePath(),
                "-Tpng",
                "-o",
                pngFile.getAbsolutePath(),
                dotFile.getAbsolutePath()
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            String line = null;
            while((line = reader.readLine()) != null)
                root.printWarning(line);
            int result = p.waitFor();
            if (result != 0)
                root.printWarning("Errors running Graphviz on " + dotFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Takes an HTML file, looks for the first instance of the specified insertion point, and
     * inserts the diagram image reference and a client side map in that point.
     */
    private static void alterHtmlDocs(String outputFolder, String packageName, String className,
            String htmlFileName, Pattern insertPointPattern, RootDoc root) throws IOException {
        // setup files
        File output = new File(outputFolder, packageName.replace(".", "/"));
        File htmlFile = new File(output, htmlFileName);
        File alteredFile = new File(htmlFile.getAbsolutePath() + ".uml");
        File mapFile = new File(output, className + ".map");
        if (!htmlFile.exists()) {
            System.err.println("Expected file not found: " + htmlFile.getAbsolutePath());
            return;
        }

        // parse & rewrite
        BufferedWriter writer = null;
        BufferedReader reader = null;
        boolean matched = false;
        try {
            int BUFSIZE = (int) Math.pow(2, 20); // more or less one megabyte
            writer = new BufferedWriter(new FileWriter(alteredFile), BUFSIZE);
            reader = new BufferedReader(new FileReader(htmlFile));

            String line;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
                if (!matched && insertPointPattern.matcher(line).matches()) {
                    matched = true;
                    if (mapFile.exists())
                        insertClientSideMap(mapFile, writer);
                    else
                        root.printWarning("Could not find map file " + mapFile);
                    writer.write("<div align=\"center\"><img src=\"" + className
                            + ".png\" alt=\"Package class diagram package " + className
                            + "\" usemap=\"#G\" border=0/></a></div>");
                    writer.newLine();
                }
            }
        } finally {
            if (writer != null)
                writer.close();
            if (reader != null)
                reader.close();
        }

        // if altered, delete old file and rename new one to the old file name
        if (matched) {
            htmlFile.delete();
            alteredFile.renameTo(htmlFile);
        } else {
            root.printNotice("Warning, could not find a line that matches the pattern '" + insertPointPattern.pattern() 
                    + "'.\n Class diagram reference not inserted");
            alteredFile.delete();
        }
    }

    /**
     * Reads the map file and outputs in to the specified writer
     * @throws IOException
     */
    private static void insertClientSideMap(File mapFile, BufferedWriter writer) throws IOException {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(mapFile));
            String line = null;
            while ((line = reader.readLine()) != null) {
                writer.write(line);
                writer.newLine();
            }
        } finally {
            if (reader != null)
                reader.close();
        }
    }

    /**
     * Returns the output path specified on the javadoc options
     */
    private static String findOutputPath(String[][] options) {
        for (int i = 0; i < options.length; i++) {
            if (options[i][0].equals("-d"))
                return options[i][1];
        }
        return ".";
    }

}
