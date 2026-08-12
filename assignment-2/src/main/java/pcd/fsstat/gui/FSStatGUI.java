package pcd.fsstat.gui;

import io.reactivex.rxjava3.disposables.Disposable;
import pcd.fsstat.common.FSReport;
import pcd.fsstat.common.FSReportJob;
import pcd.fsstat.common.FSReportListener;
import pcd.fsstat.common.SizeUnit;
import pcd.fsstat.paradigm.eventloop.EventLoopFSStat;
import pcd.fsstat.paradigm.reactive.ReactiveFSStat;
import pcd.fsstat.paradigm.virtualthreads.VirtualThreadsFSStat;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;

/** Swing interface for interactive FSStat scans. */
public class FSStatGUI extends JFrame {
    private static final String PARADIGM_VT = "Virtual Threads";
    private static final String PARADIGM_RX = "Reactive Programming (Rx)";
    private static final String PARADIGM_LOOP = "Event-Loop (Vert.x)";

    private final JTextField directoryPathField;
    private final JSpinner maximumFileSizeSpinner;
    private final JComboBox<SizeUnit> maximumFileSizeUnitCombo;
    private final JSpinner bandCountSpinner;
    private final JComboBox<String> paradigmSelector;
    private final JButton startScanButton;
    private final JButton cancelScanButton;
    private final JLabel totalFileCountValue;
    private final JLabel durationValue;
    private final JProgressBar scanProgressBar;
    private final JTable resultsTable;
    private final DefaultTableModel resultsTableModel;
    private final JLabel statusMessageLabel;

    private FSReportJob currentScanJob;
    private Disposable reactiveSubscription;
    private boolean scanInProgress = false;

    /** Builds the full Swing interface and wires the user actions. */
    public FSStatGUI() {
        setTitle("FSStat - Filesystem Statistics Analyzer");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // --- TOP PANEL: Configuration ---
        JPanel configurationPanel = new JPanel(new GridBagLayout());
        configurationPanel.setBorder(BorderFactory.createTitledBorder("Configuration"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(5, 5, 5, 5);
        constraints.fill = GridBagConstraints.HORIZONTAL;

        // Directory Selector
        constraints.gridx = 0; constraints.gridy = 0;
        configurationPanel.add(new JLabel("Directory:"), constraints);

        directoryPathField = new JTextField(System.getProperty("user.home"), 30);
        constraints.gridx = 1; constraints.gridwidth = 2;
        configurationPanel.add(directoryPathField, constraints);

        JButton browseButton = new JButton("Browse...");
        constraints.gridx = 3; constraints.gridwidth = 1;
        configurationPanel.add(browseButton, constraints);

        // MaxFS and NB
        constraints.gridx = 0; constraints.gridy = 1;
        configurationPanel.add(new JLabel("Max File Size:"), constraints);

        maximumFileSizeSpinner = new JSpinner(new SpinnerNumberModel(10.0, 0.1, 1024.0 * 1024 * 1024 * 1024, 1.0));
        constraints.gridx = 1;
        configurationPanel.add(maximumFileSizeSpinner, constraints);

        maximumFileSizeUnitCombo = new JComboBox<>(new SizeUnit[] {
            SizeUnit.BYTES,
            SizeUnit.KILOBYTES,
            SizeUnit.MEGABYTES,
            SizeUnit.GIGABYTES
        });
        maximumFileSizeUnitCombo.setSelectedItem(SizeUnit.MEGABYTES);
        constraints.gridx = 2;
        configurationPanel.add(maximumFileSizeUnitCombo, constraints);

        constraints.gridx = 3;
        configurationPanel.add(new JLabel("Number of Bands:"), constraints);

        bandCountSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));
        constraints.gridx = 4;
        configurationPanel.add(bandCountSpinner, constraints);

        // Paradigm Selector
        constraints.gridx = 0; constraints.gridy = 2;
        configurationPanel.add(new JLabel("Programming Paradigm:"), constraints);

        paradigmSelector = new JComboBox<>(new String[]{
            "Virtual Threads",
            "Reactive Programming (Rx)",
            "Event-Loop (Vert.x)"
        });
        constraints.gridx = 1; constraints.gridwidth = 2;
        configurationPanel.add(paradigmSelector, constraints);

        // Action Buttons
        JPanel actionButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        startScanButton = new JButton("Start Scan");
        cancelScanButton = new JButton("Cancel");
        cancelScanButton.setEnabled(false);
        actionButtonPanel.add(startScanButton);
        actionButtonPanel.add(cancelScanButton);

        constraints.gridx = 4; constraints.gridwidth = 1;
        configurationPanel.add(actionButtonPanel, constraints);

        add(configurationPanel, BorderLayout.NORTH);

        // --- CENTER PANEL: Results and Progress ---
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Stats summary row
        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        summaryPanel.setBorder(BorderFactory.createEtchedBorder());
        summaryPanel.add(new JLabel("Total Files:"));
        totalFileCountValue = new JLabel("0");
        totalFileCountValue.setFont(totalFileCountValue.getFont().deriveFont(Font.BOLD));
        summaryPanel.add(totalFileCountValue);

        summaryPanel.add(new JLabel("Execution Time:"));
        durationValue = new JLabel(FSReport.formatDuration(0));
        durationValue.setFont(durationValue.getFont().deriveFont(Font.BOLD));
        summaryPanel.add(durationValue);
        centerPanel.add(summaryPanel, BorderLayout.NORTH);

        // Results Table
        resultsTableModel = new DefaultTableModel(new Object[]{"Size Band Range", "File Count"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultsTable = new JTable(resultsTableModel);
        resultsTable.setFillsViewportHeight(true);
        centerPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        // Progress bar
        scanProgressBar = new JProgressBar();
        scanProgressBar.setIndeterminate(false);
        centerPanel.add(scanProgressBar, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

        // --- SOUTH PANEL: Status Bar ---
        JPanel statusBarPanel = new JPanel(new BorderLayout());
        statusBarPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        statusMessageLabel = new JLabel(" Ready");
        statusBarPanel.add(statusMessageLabel, BorderLayout.WEST);
        add(statusBarPanel, BorderLayout.SOUTH);

        // --- BUTTON ACTIONS ---
        // Directory selection is kept on the EDT because it opens a Swing chooser.
        browseButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setCurrentDirectory(new File(directoryPathField.getText()));
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                directoryPathField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        startScanButton.addActionListener(e -> beginScan());
        cancelScanButton.addActionListener(e -> cancelCurrentScan());
    }

    /** Validates form inputs and starts the selected scan implementation. */
    private void beginScan() {
        String directoryPath = directoryPathField.getText().trim();
        if (directoryPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a valid directory path.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File targetDirectory = new File(directoryPath);
        if (!targetDirectory.exists() || !targetDirectory.isDirectory()) {
            JOptionPane.showMessageDialog(this, "The specified path is not a directory.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        double maximumFileSizeInput = ((Number) maximumFileSizeSpinner.getValue()).doubleValue();
        SizeUnit sizeUnit = (SizeUnit) maximumFileSizeUnitCombo.getSelectedItem();
        int numberOfBands = (Integer) bandCountSpinner.getValue();
        String selectedParadigm = (String) paradigmSelector.getSelectedItem();
        long maximumFileSizeBytes = sizeUnit.toBytes(maximumFileSizeInput);

        prepareScanUi(selectedParadigm, maximumFileSizeBytes, numberOfBands, sizeUnit);

        launchScanForParadigm(directoryPath, maximumFileSizeBytes, numberOfBands, selectedParadigm, sizeUnit);
    }

    /** Cancels the currently running scan and restores the idle UI state. */
    private void cancelCurrentScan() {
        if (!scanInProgress) return;

        // Cancel whichever asynchronous backend is currently active.
        if (currentScanJob != null) {
            currentScanJob.cancel();
            currentScanJob = null;
        }
        if (reactiveSubscription != null) {
            reactiveSubscription.dispose();
            reactiveSubscription = null;
        }

        scanProgressBar.setIndeterminate(false);
        statusMessageLabel.setText(" Scan cancelled.");
        setScanInProgress(false);
    }

    /** Refreshes summary labels and size-band rows from a report snapshot. */
    private void updateResultsView(FSReport report, SizeUnit displayUnit) {
        totalFileCountValue.setText(String.format("%,d", report.totalFiles()));
        durationValue.setText(report.formatDuration());

        long[] bandCounts = report.bandsCount();
        for (int bandIndex = 0; bandIndex < bandCounts.length; bandIndex++) {
            if (bandIndex < resultsTableModel.getRowCount()) {
                resultsTableModel.setValueAt(bandCounts[bandIndex], bandIndex, 1);
                resultsTableModel.setValueAt(report.getBandLabel(bandIndex, displayUnit), bandIndex, 0);
            }
        }
    }

    /** Marks the scan as successfully finished in the UI. */
    private void finishScan(String message) {
        scanProgressBar.setIndeterminate(false);
        statusMessageLabel.setText(" " + message);
        setScanInProgress(false);
        currentScanJob = null;
        reactiveSubscription = null;
    }

    /** Marks the scan as failed and shows the error dialog. */
    private void failScan(String errorMessage) {
        scanProgressBar.setIndeterminate(false);
        statusMessageLabel.setText(" Error: " + errorMessage);
        setScanInProgress(false);
        currentScanJob = null;
        reactiveSubscription = null;
        JOptionPane.showMessageDialog(this, errorMessage, "Scan Error", JOptionPane.ERROR_MESSAGE);
    }

    /** Enables or disables controls according to whether a scan is running. */
    private void setScanInProgress(boolean inProgress) {
        this.scanInProgress = inProgress;
        startScanButton.setEnabled(!inProgress);
        cancelScanButton.setEnabled(inProgress);
        directoryPathField.setEnabled(!inProgress);
        maximumFileSizeSpinner.setEnabled(!inProgress);
        maximumFileSizeUnitCombo.setEnabled(!inProgress);
        bandCountSpinner.setEnabled(!inProgress);
        paradigmSelector.setEnabled(!inProgress);
    }

    /** Clears previous results and prepares the table for a new scan. */
    private void prepareScanUi(String paradigm, long maximumFileSizeBytes, int numberOfBands, SizeUnit sizeUnit) {
        resultsTableModel.setRowCount(0);
        for (int bandIndex = 0; bandIndex <= numberOfBands; bandIndex++) {
            resultsTableModel.addRow(new Object[]{FSReport.formatBandLabel(maximumFileSizeBytes, numberOfBands, bandIndex, sizeUnit), 0L});
        }
        totalFileCountValue.setText("0");
        durationValue.setText(FSReport.formatDuration(0));
        scanProgressBar.setIndeterminate(true);
        statusMessageLabel.setText(" Scanning using " + paradigm + "...");
        setScanInProgress(true);
    }

    /** Creates a listener that safely forwards scan callbacks onto the Swing EDT. */
    private FSReportListener createGuiListener(SizeUnit sizeUnit) {
        return new FSReportListener() {
            @Override
            public void onUpdate(FSReport report) {
                SwingUtilities.invokeLater(() -> updateResultsView(report, sizeUnit));
            }

            @Override
            public void onCompleted(FSReport report) {
                SwingUtilities.invokeLater(() -> {
                    updateResultsView(report, sizeUnit);
                    finishScan("Scan completed in " + report.formatDuration() + ".");
                });
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> failScan(error.getMessage()));
            }
        };
    }

    /** Routes the GUI scan request to the selected backend. */
    private void launchScanForParadigm(String directoryPath, long maximumFileSizeBytes, int numberOfBands, String paradigm, SizeUnit sizeUnit) {
        if (PARADIGM_VT.equals(paradigm)) {
            launchVirtualThreadsScan(directoryPath, maximumFileSizeBytes, numberOfBands, sizeUnit);
            return;
        }
        if (PARADIGM_LOOP.equals(paradigm)) {
            launchEventLoopScan(directoryPath, maximumFileSizeBytes, numberOfBands, sizeUnit);
            return;
        }
        if (PARADIGM_RX.equals(paradigm)) {
            launchReactiveScan(directoryPath, maximumFileSizeBytes, numberOfBands, sizeUnit);
        }
    }

    /** Starts a virtual-thread scan from the GUI. */
    private void launchVirtualThreadsScan(String directoryPath, long maximumFileSizeBytes, int numberOfBands, SizeUnit sizeUnit) {
        currentScanJob = VirtualThreadsFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands, createGuiListener(sizeUnit));
    }

    /** Starts a Vert.x event-loop scan from the GUI. */
    private void launchEventLoopScan(String directoryPath, long maximumFileSizeBytes, int numberOfBands, SizeUnit sizeUnit) {
        currentScanJob = EventLoopFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands, createGuiListener(sizeUnit));
    }

    /** Starts an RxJava scan from the GUI and keeps its subscription for cancellation. */
    private void launchReactiveScan(String directoryPath, long maximumFileSizeBytes, int numberOfBands, SizeUnit sizeUnit) {
        final FSReport[] latestReport = new FSReport[1];
        reactiveSubscription = ReactiveFSStat.getFSReport(directoryPath, maximumFileSizeBytes, numberOfBands)
            .subscribe(
                report -> {
                    latestReport[0] = report;
                    SwingUtilities.invokeLater(() -> updateResultsView(report, sizeUnit));
                },
                error -> SwingUtilities.invokeLater(() -> failScan(error.getMessage())),
                () -> SwingUtilities.invokeLater(() -> {
                    if (latestReport[0] != null) {
                        finishScan("Scan completed in " + latestReport[0].formatDuration() + ".");
                    } else {
                        finishScan("Scan completed.");
                    }
                })
            );
    }

    /** Opens the Swing GUI. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                // Use the native system style when it is available.
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            FSStatGUI frame = new FSStatGUI();
            frame.setVisible(true);
        });
    }
}
