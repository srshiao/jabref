package org.jabref.gui.maintable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.swing.undo.UndoManager;

import javafx.collections.ListChangeListener;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseDragEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;

import org.jabref.gui.DialogService;
import org.jabref.gui.DragAndDropDataFormats;
import org.jabref.gui.Globals;
import org.jabref.gui.LibraryTab;
import org.jabref.gui.StateManager;
import org.jabref.gui.actions.StandardActions;
import org.jabref.gui.actions.NavigateAction;
import org.jabref.gui.edit.EditAction;
import org.jabref.gui.externalfiles.ImportHandler;
import org.jabref.gui.externalfiletype.ExternalFileTypes;
import org.jabref.gui.keyboard.KeyBinding;
import org.jabref.gui.keyboard.KeyBindingRepository;
import org.jabref.gui.maintable.columns.MainTableColumn;
import org.jabref.gui.util.ControlHelper;
import org.jabref.gui.util.CustomLocalDragboard;
import org.jabref.gui.util.DefaultTaskExecutor;
import org.jabref.gui.util.ViewModelTableRowFactory;
import org.jabref.logic.importer.ImportCleanup;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.OS;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.database.BibDatabaseMode;
import org.jabref.model.database.event.EntriesAddedEvent;
import org.jabref.model.entry.BibEntry;
import org.jabref.preferences.PreferencesService;

import com.google.common.eventbus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MainTable extends TableView<BibEntryTableViewModel> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MainTable.class);
    private static final int MAX_BACK_HISTORY_SIZE = 10;

    private final LibraryTab libraryTab;
    private final DialogService dialogService;
    private final BibDatabaseContext database;
    private final MainTableDataModel model;

    private final ImportHandler importHandler;
    private final CustomLocalDragboard localDragboard;

    private long lastKeyPressTime;
    private String columnSearchTerm;

    private final List<BibEntry> previousEntries = new ArrayList<>();
    private final List<BibEntry> nextEntries = new ArrayList<>();
    private BibEntry showing;
    // Variable to prevent erroneous update of back/forward histories at the time
    // when a Back or Forward operation is being processed:
    private boolean backOrForwardInProgress;

    public MainTable(MainTableDataModel model,
                     LibraryTab libraryTab,
                     BibDatabaseContext database,
                     PreferencesService preferencesService,
                     DialogService dialogService,
                     StateManager stateManager,
                     ExternalFileTypes externalFileTypes,
                     KeyBindingRepository keyBindingRepository) {
        super();

        this.libraryTab = libraryTab;
        this.dialogService = dialogService;
        this.database = Objects.requireNonNull(database);
        this.model = model;
        UndoManager undoManager = libraryTab.getUndoManager();
        MainTablePreferences mainTablePreferences = preferencesService.getMainTablePreferences();

        importHandler = new ImportHandler(
                database, externalFileTypes,
                preferencesService,
                Globals.getFileUpdateMonitor(),
                undoManager,
                stateManager);

        localDragboard = stateManager.getLocalDragboard();

        this.setOnDragOver(this::handleOnDragOverTableView);
        this.setOnDragDropped(this::handleOnDragDroppedTableView);

        this.getColumns().addAll(
                new MainTableColumnFactory(
                        database,
                        preferencesService,
                        externalFileTypes,
                        libraryTab.getUndoManager(),
                        dialogService,
                        stateManager).createColumns());

        new ViewModelTableRowFactory<BibEntryTableViewModel>()
                .withOnMouseClickedEvent((entry, event) -> {
                    if (event.getClickCount() == 2) {
                        libraryTab.showAndEdit(entry.getEntry());
                        newEntryShowing(entry.getEntry());
                    }
                })
                .withContextMenu(entry -> RightClickMenu.create(entry,
                        keyBindingRepository,
                        libraryTab,
                        dialogService,
                        stateManager,
                        preferencesService,
                        undoManager,
                        Globals.getClipboardManager()))
                .setOnDragDetected(this::handleOnDragDetected)
                .setOnDragDropped(this::handleOnDragDropped)
                .setOnDragOver(this::handleOnDragOver)
                .setOnDragExited(this::handleOnDragExited)
                .setOnMouseDragEntered(this::handleOnDragEntered)
                .install(this);

        this.getSortOrder().clear();

        /* KEEP for debugging purposes
        for (var colModel : mainTablePreferences.getColumnPreferences().getColumnSortOrder()) {
            for (var col : this.getColumns()) {
                var tablecColModel = ((MainTableColumn<?>) col).getModel();
                if (tablecColModel.equals(colModel)) {
                    LOGGER.debug("Adding sort order for col {} ", col);
                    this.getSortOrder().add(col);
                    break;
                }
            }
        }
        */

        mainTablePreferences.getColumnPreferences().getColumnSortOrder().forEach(columnModel ->
                this.getColumns().stream()
                    .map(column -> (MainTableColumn<?>) column)
                    .filter(column -> column.getModel().equals(columnModel))
                    .findFirst()
                    .ifPresent(column -> this.getSortOrder().add(column)));

        if (mainTablePreferences.getResizeColumnsToFit()) {
            this.setColumnResizePolicy(new SmartConstrainedResizePolicy());
        }

        this.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        this.setItems(model.getEntriesFilteredAndSorted());

        // Enable sorting
        model.getEntriesFilteredAndSorted().comparatorProperty().bind(this.comparatorProperty());

        this.getStylesheets().add(MainTable.class.getResource("MainTable.css").toExternalForm());

        // Store visual state
        new PersistenceVisualStateTable(this, preferencesService);

        setupKeyBindings(keyBindingRepository);

        this.setOnKeyTyped(key -> {
            if (this.getSortOrder().isEmpty()) {
                return;
            }
            this.jumpToSearchKey(getSortOrder().get(0), key);
        });

        database.getDatabase().registerListener(this);
    }

    /**
     * This is called, if a user starts typing some characters into the keyboard with focus on main table. The {@link MainTable} will scroll to the cell with the same starting column value and typed string
     *
     * @param sortedColumn The sorted column in {@link MainTable}
     * @param keyEvent     The pressed character
     */

    private void jumpToSearchKey(TableColumn<BibEntryTableViewModel, ?> sortedColumn, KeyEvent keyEvent) {
        if ((keyEvent.getCharacter() == null) || (sortedColumn == null)) {
            return;
        }

        if ((System.currentTimeMillis() - lastKeyPressTime) < 700) {
            columnSearchTerm += keyEvent.getCharacter().toLowerCase();
        } else {
            columnSearchTerm = keyEvent.getCharacter().toLowerCase();
        }

        lastKeyPressTime = System.currentTimeMillis();

        this.getItems().stream()
            .filter(item -> Optional.ofNullable(sortedColumn.getCellObservableValue(item).getValue())
                                    .map(Object::toString)
                                    .orElse("")
                                    .toLowerCase()
                                    .startsWith(columnSearchTerm))
            .findFirst()
            .ifPresent(item -> {
                this.scrollTo(item);
                this.clearAndSelect(item.getEntry());
            });
    }

    @Subscribe
    public void listen(EntriesAddedEvent event) {
        DefaultTaskExecutor.runInJavaFXThread(() -> clearAndSelect(event.getFirstEntry()));
    }

    public void clearAndSelect(BibEntry bibEntry) {
        findEntry(bibEntry).ifPresent(entry -> {
            getSelectionModel().clearSelection();
            getSelectionModel().select(entry);
            scrollTo(entry);
        });
    }

    public void copy() {
        List<BibEntry> selectedEntries = getSelectedEntries();

        if (!selectedEntries.isEmpty()) {
            try {
                Globals.getClipboardManager().setContent(selectedEntries);
                dialogService.notify(libraryTab.formatOutputMessage(Localization.lang("Copied"), selectedEntries.size()));
            } catch (IOException e) {
                LOGGER.error("Error while copying selected entries to clipboard", e);
            }
        }
    }

    public void cut() {
        copy();
        libraryTab.delete(true);
    }

    /**
    * This function connects the KeyBindings made into actions
    * we want the function to do when the key is pressed.
    *
    * @param  KeyBindingRepository keyBindings
    * @return none
    * 
    */ 
    // CS427 Issue link: https://github.com/JabRef/jabref/issues/7535

    private void setupKeyBindings(KeyBindingRepository keyBindings) {
        this.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                getSelectedEntries().stream()
                                    .findFirst()
                                    .ifPresent(libraryTab::showAndEdit);
                event.consume();
                return;
            }

            Optional<KeyBinding> keyBinding = keyBindings.mapToKeyBinding(event);
            if (keyBinding.isPresent()) {
                switch (keyBinding.get()) {
                    case SELECT_FIRST_ENTRY:
                        clearAndSelectFirst();
                        event.consume();
                        break;
                    case SELECT_LAST_ENTRY:
                        clearAndSelectLast();
                        event.consume();
                        break;
                    case PASTE:
                        if (!OS.OS_X) {
                            new EditAction(StandardActions.PASTE, libraryTab.frame(), Globals.stateManager).execute();
                        }
                        event.consume();
                        break;
                    case COPY:
                        new EditAction(StandardActions.COPY, libraryTab.frame(), Globals.stateManager).execute();
                        event.consume();
                        break;
                    case CUT:
                        new EditAction(StandardActions.CUT, libraryTab.frame(), Globals.stateManager).execute();
                        event.consume();
                        break;
                    case DELETE_ENTRY:
                        new EditAction(StandardActions.DELETE_ENTRY, libraryTab.frame(), Globals.stateManager).execute();
                        event.consume();
                        break;
                    // CS427 Issue link: https://github.com/JabRef/jabref/issues/7535
                    case BACK:
                        new NavigateAction(StandardActions.BACK, libraryTab.frame(), Globals.stateManager).execute();
                        event.consume();
                        back();
                        break;
                    // CS427 Issue link: https://github.com/JabRef/jabref/issues/7535
                    case FORWARD:
                        new NavigateAction(StandardActions.FORWARD, libraryTab.frame(), Globals.stateManager).execute();
                        event.consume();
                        forward();
                        break;
                    default:
                        // Pass other keys to parent
                }
            }
        });
    }

    public void clearAndSelectFirst() {
        getSelectionModel().clearSelection();
        getSelectionModel().selectFirst();
        scrollTo(0);
    }

    private void clearAndSelectLast() {
        getSelectionModel().clearSelection();
        getSelectionModel().selectLast();
        scrollTo(getItems().size() - 1);
    }

    /**
     * Update the pointer to the currently shown entry in all cases where the user has moved to a new entry, except when
     * using Back and Forward commands. Also updates history for Back command, and clears history for Forward command.
     *
     * @param entry The entry that is now to be shown.
     */
    private void newEntryShowing(BibEntry entry) {

        LOGGER.info("newEntryShowing() called");
        // If this call is the result of a Back or Forward operation, we must take
        // care not to make any history changes, since the necessary changes will
        // already have been done in the back() or forward() method:
        if (backOrForwardInProgress) {
            showing = entry;
            backOrForwardInProgress = false;
            return;
        }
        nextEntries.clear();
        if (!Objects.equals(entry, showing)) {
            // Add the entry we are leaving to the history:
            if (showing != null) {
                previousEntries.add(showing);
                if (previousEntries.size() > MAX_BACK_HISTORY_SIZE) {
                    previousEntries.remove(0);
                }
            }
            showing = entry;
        }
    }
    /**
    * This function is called when the user decides to backtrack and see what data was previously selected. 
    * 
    * This method keeps track of the entries in a BibEntry object and recalls the most recent 
    *  entry when prompted by the keyboard commands. Once the recent entry is selected, that
    *  entry is then deleted so it will not be repeated in the list. 
    * Function also updates new entries onto the list.
    *
    * @param  none
    * @return none
    * 
    */ 
    // CS427 Issue link: https://github.com/JabRef/jabref/issues/7535
    
    private void back() {
        LOGGER.info("back() called");
        if (!previousEntries.isEmpty()) {
            BibEntry toShow = previousEntries.get(previousEntries.size() - 1);
            previousEntries.remove(previousEntries.size() - 1);
            // Add the entry we are going back from to the Forward history:
            if (showing != null) {
                nextEntries.add(showing);
            }
            backOrForwardInProgress = true; // to avoid the history getting updated erroneously
            clearAndSelect(toShow);
        }
    }
    /**
    * This function is called when the user decides to show the next entry and see what data was selected. 
    * before they decided to go back.
    * This method keeps track of the entries in a BibEntry object and recalls the next entry
    *  entry when prompted by the keyboard commands. Once the recent entry is selected, that
    *  entry is then deleted so it will not be repeated in the list. 
    * Function also updates new entries onto the list.
    *
    * @param  none
    * @return none
    * 
    */ 
    // CS427 Issue link: https://github.com/JabRef/jabref/issues/7535
    
    private void forward() {
        LOGGER.info("forward() called");
        if (!nextEntries.isEmpty()) {
            BibEntry toShow = nextEntries.get(nextEntries.size() - 1);
            nextEntries.remove(nextEntries.size() - 1);
            // Add the entry we are going forward from to the Back history:
            if (showing != null) {
                previousEntries.add(showing);
            }
            backOrForwardInProgress = true; // to avoid the history getting updated erroneously
            clearAndSelect(toShow);
        }
    }

    public void paste(BibDatabaseMode bibDatabaseMode) {
        // Find entries in clipboard
        List<BibEntry> entriesToAdd = Globals.getClipboardManager().extractData();
        ImportCleanup cleanup = new ImportCleanup(bibDatabaseMode);
        cleanup.doPostCleanup(entriesToAdd);
        libraryTab.insertEntries(entriesToAdd);
        if (!entriesToAdd.isEmpty()) {
            this.requestFocus();
        }
    }

    private void handleOnDragOver(TableRow<BibEntryTableViewModel> row, BibEntryTableViewModel item, DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.ANY);
            ControlHelper.setDroppingPseudoClasses(row, event);
        }
        event.consume();
    }

    private void handleOnDragOverTableView(DragEvent event) {
        if (event.getDragboard().hasFiles()) {
            event.acceptTransferModes(TransferMode.ANY);
        }
        event.consume();
    }

    private void handleOnDragEntered(TableRow<BibEntryTableViewModel> row, BibEntryTableViewModel entry, MouseDragEvent event) {
        // Support the following gesture to select entries: click on one row -> hold mouse button -> move over other rows
        // We need to select all items between the starting row and the row where the user currently hovers the mouse over
        // It is not enough to just select the currently hovered row since then sometimes rows are not marked selected if the user moves to fast
        @SuppressWarnings("unchecked")
        TableRow<BibEntryTableViewModel> sourceRow = (TableRow<BibEntryTableViewModel>) event.getGestureSource();
        getSelectionModel().selectRange(sourceRow.getIndex(), row.getIndex());
    }

    private void handleOnDragExited(TableRow<BibEntryTableViewModel> row, BibEntryTableViewModel entry, DragEvent dragEvent) {
        ControlHelper.removeDroppingPseudoClasses(row);
    }

    private void handleOnDragDetected(TableRow<BibEntryTableViewModel> row, BibEntryTableViewModel entry, MouseEvent event) {
        // Start drag'n'drop
        row.startFullDrag();

        List<BibEntry> entries = getSelectionModel().getSelectedItems().stream().map(BibEntryTableViewModel::getEntry).collect(Collectors.toList());

        // The following is necesary to initiate the drag and drop in javafx, although we don't need the contents
        // It doesn't work without
        ClipboardContent content = new ClipboardContent();
        Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
        content.put(DragAndDropDataFormats.ENTRIES, "");
        dragboard.setContent(content);

        if (!entries.isEmpty()) {
            localDragboard.putBibEntries(entries);
        }

        event.consume();
    }

    private void handleOnDragDropped(TableRow<BibEntryTableViewModel> row, BibEntryTableViewModel target, DragEvent event) {
        boolean success = false;

        if (event.getDragboard().hasFiles()) {
            List<Path> files = event.getDragboard().getFiles().stream().map(File::toPath).collect(Collectors.toList());

            // Different actions depending on where the user releases the drop in the target row
            // Bottom + top -> import entries
            // Center -> link files to entry
            // Depending on the pressed modifier, move/copy/link files to drop target
            switch (ControlHelper.getDroppingMouseLocation(row, event)) {
                case TOP, BOTTOM -> importHandler.importFilesInBackground(files).executeWith(Globals.TASK_EXECUTOR);
                case CENTER -> {
                    BibEntry entry = target.getEntry();
                    switch (event.getTransferMode()) {
                        case LINK -> {
                            LOGGER.debug("Mode LINK"); // shift on win or no modifier
                            importHandler.getLinker().addFilesToEntry(entry, files);
                        }
                        case MOVE -> {
                            LOGGER.debug("Mode MOVE"); // alt on win
                            importHandler.getLinker().moveFilesToFileDirAndAddToEntry(entry, files);
                        }
                        case COPY -> {
                            LOGGER.debug("Mode Copy"); // ctrl on win
                            importHandler.getLinker().copyFilesToFileDirAndAddToEntry(entry, files);
                        }
                    }
                }
            }

            success = true;
        }

        event.setDropCompleted(success);
        event.consume();
    }

    private void handleOnDragDroppedTableView(DragEvent event) {
        boolean success = false;

        if (event.getDragboard().hasFiles()) {
            List<Path> files = event.getDragboard().getFiles().stream().map(File::toPath).collect(Collectors.toList());
            importHandler.importFilesInBackground(files).executeWith(Globals.TASK_EXECUTOR);

            success = true;
        }

        event.setDropCompleted(success);
        event.consume();
    }

    public void addSelectionListener(ListChangeListener<? super BibEntryTableViewModel> listener) {
        getSelectionModel().getSelectedItems().addListener(listener);
    }

    public MainTableDataModel getTableModel() {
        return model;
    }

    public BibEntry getEntryAt(int row) {
        return model.getEntriesFilteredAndSorted().get(row).getEntry();
    }

    public List<BibEntry> getSelectedEntries() {
        return getSelectionModel()
                .getSelectedItems()
                .stream()
                .map(BibEntryTableViewModel::getEntry)
                .collect(Collectors.toList());
    }

    private Optional<BibEntryTableViewModel> findEntry(BibEntry entry) {
        return model.getEntriesFilteredAndSorted()
                    .stream()
                    .filter(viewModel -> viewModel.getEntry().equals(entry))
                    .findFirst();
    }
}
