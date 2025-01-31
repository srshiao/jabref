package org.jabref.gui.exporter;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javafx.scene.input.ClipboardContent;

import org.jabref.gui.ClipBoardManager;
import org.jabref.gui.DialogService;
import org.jabref.gui.JabRefFrame;
import org.jabref.gui.LibraryTab;
import org.jabref.gui.actions.SimpleCommand;
import org.jabref.gui.util.BackgroundTask;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.logic.exporter.Exporter;
import org.jabref.logic.exporter.ExporterFactory;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.FileType;
import org.jabref.logic.util.OS;
import org.jabref.logic.util.StandardFileType;
import org.jabref.model.entry.BibEntry;
import org.jabref.preferences.PreferencesService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExportToClipboardAction extends SimpleCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExportToClipboardAction.class);

    // Only text based exporters can be used
    private static final Set<FileType> SUPPORTED_FILETYPES = Set.of(StandardFileType.TXT, StandardFileType.RTF, StandardFileType.RDF, StandardFileType.XML, StandardFileType.HTML, StandardFileType.CSV, StandardFileType.RIS);

    private JabRefFrame frame;
    private final DialogService dialogService;
    private LibraryTab panel;
    private final List<BibEntry> entries = new ArrayList<>();
    private final ExporterFactory exporterFactory;
    private final ClipBoardManager clipBoardManager;
    private final TaskExecutor taskExecutor;
    private final PreferencesService preferences;

    public ExportToClipboardAction(JabRefFrame frame, DialogService dialogService, ExporterFactory exporterFactory, ClipBoardManager clipBoardManager, TaskExecutor taskExecutor, PreferencesService prefs) {
        this.frame = frame;
        this.dialogService = dialogService;
        this.exporterFactory = exporterFactory;
        this.clipBoardManager = clipBoardManager;
        this.taskExecutor = taskExecutor;
        this.preferences = prefs;
    }

    public ExportToClipboardAction(LibraryTab panel, DialogService dialogService, ExporterFactory exporterFactory, ClipBoardManager clipBoardManager, TaskExecutor taskExecutor, PreferencesService prefs) {
        this.panel = panel;
        this.dialogService = dialogService;
        this.exporterFactory = exporterFactory;
        this.clipBoardManager = clipBoardManager;
        this.taskExecutor = taskExecutor;
        this.preferences = prefs;

    }

    @Override
    public void execute() {
        if (panel == null) {
            panel = frame.getCurrentLibraryTab();
        }

        if (panel.getSelectedEntries().isEmpty()) {
            dialogService.notify(Localization.lang("This operation requires one or more entries to be selected."));
            return;
        }

        List<Exporter> exporters = exporterFactory.getExporters().stream()
                                                  .sorted(Comparator.comparing(Exporter::getName))
                                                  .filter(exporter -> SUPPORTED_FILETYPES.contains(exporter.getFileType()))
                                                  .collect(Collectors.toList());

        // Find default choice, if any
        Exporter defaultChoice = exporters.stream()
                                          .filter(exporter -> exporter.getName().equals(preferences.getImportExportPreferences().getLastExportExtension()))
                                          .findAny()
                                          .orElse(null);

        Optional<Exporter> selectedExporter = dialogService.showChoiceDialogAndWait(
                Localization.lang("Export"), Localization.lang("Select export format"),
                Localization.lang("Export"), defaultChoice, exporters);

        selectedExporter.ifPresent(exporter -> BackgroundTask.wrap(() -> exportToClipboard(exporter))
                                                             .onSuccess(this::setContentToClipboard)
                                                             .onFailure(ex -> {
                                                                 LOGGER.error("Error exporting to clipboard", ex);
                                                                 dialogService.showErrorDialogAndWait("Error exporting to clipboard", ex);
                                                             })
                                                             .executeWith(taskExecutor));
    }

    private ExportResult exportToClipboard(Exporter exporter) throws Exception {
        // Set the global variable for this database's file directory before exporting,
        // so formatters can resolve linked files correctly.
        // (This is an ugly hack!)
        preferences.storeFileDirforDatabase(panel.getBibDatabaseContext()
                                                .getFileDirectories(preferences.getFilePreferences()));

        // Add chosen export type to last used preference, to become default
        preferences.storeImportExportPreferences(
               preferences.getImportExportPreferences().withLastExportExtension(exporter.getName()));

        Path tmp = null;
        try {
            // To simplify the exporter API we simply do a normal export to a temporary
            // file, and read the contents afterwards:
            tmp = Files.createTempFile("jabrefCb", ".tmp");

            entries.addAll(panel.getSelectedEntries());

            // Write to file:
            exporter.export(panel.getBibDatabaseContext(), tmp,
                    panel.getBibDatabaseContext()
                         .getMetaData()
                         .getEncoding()
                         .orElse(preferences.getGeneralPreferences().getDefaultEncoding()),
                    entries);
            // Read the file and put the contents on the clipboard:

            return new ExportResult(readFileToString(tmp), exporter.getFileType());
        } finally {
            // Clean up:
            if ((tmp != null) && Files.exists(tmp)) {
                try {
                    Files.delete(tmp);
                } catch (IOException e) {
                    LOGGER.info("Cannot delete temporary clipboard file", e);
                }
            }
        }
    }

    private void setContentToClipboard(ExportResult result) {
        ClipboardContent clipboardContent = new ClipboardContent();
        List<String> extensions = result.fileType.getExtensions();
        if (extensions.contains("html")) {
            clipboardContent.putHtml(result.content);
        } else if (extensions.contains("rtf")) {
            clipboardContent.putRtf(result.content);
        } else if (extensions.contains("rdf")) {
            clipboardContent.putRtf(result.content);
        }
        clipboardContent.putString(result.content);
        this.clipBoardManager.setContent(clipboardContent);

        dialogService.notify(Localization.lang("Entries exported to clipboard") + ": " + entries.size());
    }

    private String readFileToString(Path tmp) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(tmp, panel.getBibDatabaseContext()
                                                                       .getMetaData()
                                                                       .getEncoding()
                                                                       .orElse(preferences.getGeneralPreferences().getDefaultEncoding()))) {
            return reader.lines().collect(Collectors.joining(OS.NEWLINE));
        }
    }

    private static class ExportResult {

        final String content;
        final FileType fileType;

        ExportResult(String content, FileType fileType) {
            this.content = content;
            this.fileType = fileType;
        }
    }
}
