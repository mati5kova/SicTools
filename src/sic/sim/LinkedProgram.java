package sic.sim;

import sic.asm.Assembler;
import sic.asm.AsmError;
import sic.ast.Program;
import sic.ast.StorageSymbol;
import sic.ast.Symbol;
import sic.link.Linker;
import sic.link.LinkerError;
import sic.link.Options;
import sic.link.section.Section;
import sic.link.section.Sections;
import sic.loader.Loader;
import sic.sim.vm.Machine;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Assembles linked sources while retaining the symbols absent from object files. */
final class LinkedProgram {
    final HashMap<Integer, Symbol> labels = new HashMap<>();
    final HashMap<Integer, StorageSymbol> dataLabels = new HashMap<>();
    long instructionCount;
    private byte[] code;
    private int entry;

    static LinkedProgram assemble(List<String> filenames, String main) throws IOException, LinkerError {
        LinkedProgram result = new LinkedProgram();
        Map<String, sic.ast.Section> sourceSections = new HashMap<>();
        Map<String, Integer> nameCounts = new HashMap<>();
        List<String> objects = new ArrayList<>();
        List<Path> temporaryFiles = new ArrayList<>();
        Path directory = Files.createTempDirectory("sictools-link-");
        try {
            for (String filename : filenames) {
                Assembler assembler = new Assembler();
                Program program = assembler.assemble(new String(Files.readAllBytes(Paths.get(filename)), StandardCharsets.UTF_8));
                checkErrors(assembler, filename);
                Path object = directory.resolve(objects.size() + ".obj");
                temporaryFiles.add(object);
                try (Writer writer = Files.newBufferedWriter(object, StandardCharsets.UTF_8)) {
                    assembler.generateObj(program, writer, false);
                }
                checkErrors(assembler, filename);
                objects.add(object.toString());
                result.instructionCount += program.countInstructions();

                // Section ownership must survive until linking: different sections start at address zero.
                for (sic.ast.Section section : program.sections) {
                    String name = section.isDefault() ? String.valueOf(program.name()) : section.name;
                    if (sourceSections.putIfAbsent(name, section) != null)
                        throw new IOException("Duplicate section " + name + " in " + filename);
                    for (Symbol symbol : section.symbols.labels())
                        nameCounts.merge(symbol.name, 1, Integer::sum);
                }
            }

            Options options = new Options();
            options.setMain(main);
            Linker linker = new Linker(objects, options);
            Sections sections = linker.parse();
            Section mainSection = sections.getSections().get(0);
            long entryOffset = mainSection.getERecord().getStartAddr();
            Section combined = linker.passAndCombine(sections);
            // combine() uses the section start; END may select a later instruction in that section.
            combined.getERecord().setStartAddr(mainSection.getStart() + entryOffset);
            if (combined.getLength() > Machine.MAX_ADDRESS + 1L || combined.getLength() <= 0
                    || combined.getERecord().getStartAddr() < 0
                    || combined.getERecord().getStartAddr() >= combined.getLength())
                throw new IOException("Linked program or entry point is outside simulator memory.");

            for (Section section : sections.getSections()) {
                List<Symbol> symbols = sourceSections.get(section.getName()).symbols.labels();
                Collections.sort(symbols);
                for (Symbol symbol : symbols) {
                    int address = (int) section.getStart() + symbol.value();
                    String name = !symbol.isExported() && nameCounts.get(symbol.name) > 1
                            ? section.getName() + "." + symbol.name : symbol.name;
                    // Watch reads symbol.value(), so relocating only the map key would watch the wrong memory.
                    Symbol relocated = symbol instanceof StorageSymbol
                            ? new StorageSymbol(name, address, (StorageSymbol) symbol)
                            : new Symbol(name, symbol.loc, address, false);
                    relocated.setScope(symbol.scope());
                    Symbol previous = result.labels.get(address);
                    if (previous == null || (!symbol.name.equals(section.getName())
                            && (previous.name.equals(section.getName())
                            || (relocated.isExported() && !previous.isExported()))))
                        result.labels.put(address, relocated);
                    if (relocated instanceof StorageSymbol)
                        result.dataLabels.put(address, (StorageSymbol) relocated);
                }
            }

            Path output = directory.resolve("linked.obj");
            temporaryFiles.add(output);
            options.setOutputPath(output.toString());
            new sic.link.utils.Writer(combined, options).write();
            Machine staged = new Machine();
            String objectCode = new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
            if (!Loader.loadSection(staged, new StringReader(objectCode)))
                throw new IOException("Cannot load linked program.");
            result.code = Arrays.copyOf(staged.memory.memory, (int) combined.getLength());
            result.entry = staged.registers.getPC();
            return result;
        } finally {
            for (Path file : temporaryFiles) Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }

    private static void checkErrors(Assembler assembler, String filename) throws IOException {
        if (assembler.errorCatcher.shouldEnd()) {
            StringBuilder message = new StringBuilder(filename);
            for (AsmError error : assembler.errorCatcher.errs) message.append('\n').append(error);
            throw new IOException(message.toString());
        }
        assembler.errorCatcher.print();
    }

    void load(Machine machine) {
        System.arraycopy(code, 0, machine.memory.memory, 0, code.length);
        machine.registers.setPC(entry);
    }
}
