package pcd.assignment2.gui;

import io.reactivex.rxjava3.disposables.Disposable;
import pcd.assignment2.common.FSReport;
import pcd.assignment2.common.FSReportJob;
import pcd.assignment2.common.FSReportListener;
import pcd.assignment2.common.SizeUnit;
import pcd.assignment2.eventloop.EventLoopFSStat;
import pcd.assignment2.reactive.ReactiveFSStat;
import pcd.assignment2.virtualthreads.VirtualThreadsFSStat;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;

/**
 * Interactive Swing GUI application to demonstrate the filesystem size distribution analysis.
 * Supports configuring targets, selecting programming paradigms, and running/cancelling scans.
 */
public class FSStatGUI extends JFrame {

    private final JTextField dirField;
    private final JSpinner maxFsSpinner;
    private final JComboBox<SizeUnit> maxFsUnitCombo;
    private final JSpinner nbSpinner;
    private final JComboBox<String> paradigmCombo;
    private final JButton startBtn;
    private final JButton stopBtn;
    private final JLabel totalFilesVal;
    private final JLabel durationVal;
    private final JProgressBar progressBar;
    private final JTable resultsTable;
    private final DefaultTableModel tableModel;
    private final JLabel statusLabel;

    private FSReportJob currentJob;
    private Disposable rxDisposable;
    private boolean isRunning = false;

    public FSStatGUI() {
        setTitle("FSStat - Filesystem Statistics Analyzer");
        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        // --- TOP PANEL: Configuration ---
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(BorderFactory.createTitledBorder("Configuration"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Directory Selector
        gbc.gridx = 0; gbc.gridy = 0;
        configPanel.add(new JLabel("Directory:"), gbc);

        dirField = new JTextField(System.getProperty("user.home"), 30);
        gbc.gridx = 1; gbc.gridwidth = 2;
        configPanel.add(dirField, gbc);

        JButton browseBtn = new JButton("Browse...");
        gbc.gridx = 3; gbc.gridwidth = 1;
        configPanel.add(browseBtn, gbc);

        // MaxFS and NB
        gbc.gridx = 0; gbc.gridy = 1;
        configPanel.add(new JLabel("Max File Size:"), gbc);

        maxFsSpinner = new JSpinner(new SpinnerNumberModel(10.0, 0.1, 1024.0 * 1024 * 1024 * 1024, 1.0));
        gbc.gridx = 1;
        configPanel.add(maxFsSpinner, gbc);

        maxFsUnitCombo = new JComboBox<>(new SizeUnit[] {
            SizeUnit.BYTES,
            SizeUnit.KILOBYTES,
            SizeUnit.MEGABYTES,
            SizeUnit.GIGABYTES
        });
        maxFsUnitCombo.setSelectedItem(SizeUnit.MEGABYTES);
        gbc.gridx = 2;
        configPanel.add(maxFsUnitCombo, gbc);

        gbc.gridx = 3;
        configPanel.add(new JLabel("Number of Bands:"), gbc);

        nbSpinner = new JSpinner(new SpinnerNumberModel(5, 1, 100, 1));
        gbc.gridx = 4;
        configPanel.add(nbSpinner, gbc);

        // Paradigm Selector
        gbc.gridx = 0; gbc.gridy = 2;
        configPanel.add(new JLabel("Programming Paradigm:"), gbc);

        paradigmCombo = new JComboBox<>(new String[]{
            "Virtual Threads",
            "Reactive Programming (Rx)",
            "Event-Loop (Vert.x)"
        });
        gbc.gridx = 1; gbc.gridwidth = 2;
        configPanel.add(paradigmCombo, gbc);

        // Action Buttons
        JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        startBtn = new JButton("Start Scan");
        stopBtn = new JButton("Cancel");
        stopBtn.setEnabled(false);
        actionPanel.add(startBtn);
        actionPanel.add(stopBtn);

        gbc.gridx = 4; gbc.gridwidth = 1;
        configPanel.add(actionPanel, gbc);

        add(configPanel, BorderLayout.NORTH);

        // --- CENTER PANEL: Results and Progress ---
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Stats summary row
        JPanel summaryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 5));
        summaryPanel.setBorder(BorderFactory.createEtchedBorder());
        summaryPanel.add(new JLabel("Total Files:"));
        totalFilesVal = new JLabel("0");
        totalFilesVal.setFont(totalFilesVal.getFont().deriveFont(Font.BOLD));
        summaryPanel.add(totalFilesVal);

        summaryPanel.add(new JLabel("Execution Time:"));
        durationVal = new JLabel(FSReport.formatDuration(0));
        durationVal.setFont(durationVal.getFont().deriveFont(Font.BOLD));
        summaryPanel.add(durationVal);
        centerPanel.add(summaryPanel, BorderLayout.NORTH);

        // Results Table
        tableModel = new DefaultTableModel(new Object[]{"Size Band Range", "File Count"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setFillsViewportHeight(true);
        centerPanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);

        // Progress bar
        progressBar = new JProgressBar();
        progressBar.setIndeterminate(false);
        centerPanel.add(progressBar, BorderLayout.SOUTH);

        add(centerPanel, BorderLayout.CENTER);

        // --- SOUTH PANEL: Status Bar ---
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(BorderFactory.createLoweredBevelBorder());
        statusLabel = new JLabel(" Ready");
        statusPanel.add(statusLabel, BorderLayout.WEST);
        add(statusPanel, BorderLayout.SOUTH);

        // --- BUTTON ACTIONS ---
        browseBtn.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setCurrentDirectory(new File(dirField.getText()));
            int result = chooser.showOpenDialog(this);
            if (result == JFileChooser.APPROVE_OPTION) {
                dirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        startBtn.addActionListener(e -> startScan());
        stopBtn.addActionListener(e -> cancelScan());
    }

    private void startScan() {
        String path = dirField.getText().trim();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a valid directory path.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File dir = new File(path);
        if (!dir.exists() || !dir.isDirectory()) {
            JOptionPane.showMessageDialog(this, "The specified path is not a directory.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        double maxFSInput = ((Number) maxFsSpinner.getValue()).doubleValue();
        SizeUnit sizeUnit = (SizeUnit) maxFsUnitCombo.getSelectedItem();
        int nb = (Integer) nbSpinner.getValue();
        String paradigm = (String) paradigmCombo.getSelectedItem();
        long maxFS = sizeUnit.toBytes(maxFSInput);

        // UI Reset
        tableModel.setRowCount(0);
        for (int i = 0; i <= nb; i++) {
            tableModel.addRow(new Object[]{getBandRangeLabel(i, maxFS, nb, sizeUnit), 0L});
        }
        totalFilesVal.setText("0");
        durationVal.setText(FSReport.formatDuration(0));
        progressBar.setIndeterminate(true);
        statusLabel.setText(" Scanning using " + paradigm + "...");
        setRunningState(true);

        if ("Virtual Threads".equals(paradigm)) {
            currentJob = VirtualThreadsFSStat.getFSReport(path, maxFS, nb, new FSReportListener() {
                @Override
                public void onUpdate(FSReport report) {
                    SwingUtilities.invokeLater(() -> updateUI(report, sizeUnit));
                }

                @Override
                public void onCompleted(FSReport report) {
                    SwingUtilities.invokeLater(() -> {
                        updateUI(report, sizeUnit);
                        scanFinished("Scan completed in " + report.formatDuration() + ".");
                    });
                }

                @Override
                public void onError(Throwable error) {
                    SwingUtilities.invokeLater(() -> scanFailed(error.getMessage()));
                }
            });
        } else if ("Event-Loop (Vert.x)".equals(paradigm)) {
            currentJob = EventLoopFSStat.getFSReport(path, maxFS, nb, new FSReportListener() {
                @Override
                public void onUpdate(FSReport report) {
                    SwingUtilities.invokeLater(() -> updateUI(report, sizeUnit));
                }

                @Override
                public void onCompleted(FSReport report) {
                    SwingUtilities.invokeLater(() -> {
                        updateUI(report, sizeUnit);
                        scanFinished("Scan completed in " + report.formatDuration() + ".");
                    });
                }

                @Override
                public void onError(Throwable error) {
                    SwingUtilities.invokeLater(() -> scanFailed(error.getMessage()));
                }
            });
        } else if ("Reactive Programming (Rx)".equals(paradigm)) {
            final FSReport[] lastReport = new FSReport[1];
            rxDisposable = ReactiveFSStat.getFSReport(path, maxFS, nb)
                .subscribe(
                    report -> {
                        lastReport[0] = report;
                        SwingUtilities.invokeLater(() -> updateUI(report, sizeUnit));
                    },
                    error -> SwingUtilities.invokeLater(() -> scanFailed(error.getMessage())),
                    () -> SwingUtilities.invokeLater(() -> {
                        if (lastReport[0] != null) {
                            scanFinished("Scan completed in " + lastReport[0].formatDuration() + ".");
                        } else {
                            scanFinished("Scan completed.");
                        }
                    })
                );
        }
    }

    private void cancelScan() {
        if (!isRunning) return;

        if (currentJob != null) {
            currentJob.cancel();
            currentJob = null;
        }
        if (rxDisposable != null) {
            rxDisposable.dispose();
            rxDisposable = null;
        }

        progressBar.setIndeterminate(false);
        statusLabel.setText(" Scan cancelled.");
        setRunningState(false);
    }

    private void updateUI(FSReport report, SizeUnit displayUnit) {
        totalFilesVal.setText(String.format("%,d", report.totalFiles()));
        durationVal.setText(report.formatDuration());

        long[] bands = report.bandsCount();
        for (int i = 0; i < bands.length; i++) {
            if (i < tableModel.getRowCount()) {
                tableModel.setValueAt(bands[i], i, 1);
                tableModel.setValueAt(getBandRangeLabel(i, report.maxFS(), report.nb(), displayUnit), i, 0);
            }
        }
    }

    private void scanFinished(String msg) {
        progressBar.setIndeterminate(false);
        statusLabel.setText(" " + msg);
        setRunningState(false);
        currentJob = null;
        rxDisposable = null;
    }

    private void scanFailed(String errorMsg) {
        progressBar.setIndeterminate(false);
        statusLabel.setText(" Error: " + errorMsg);
        setRunningState(false);
        currentJob = null;
        rxDisposable = null;
        JOptionPane.showMessageDialog(this, errorMsg, "Scan Error", JOptionPane.ERROR_MESSAGE);
    }

    private void setRunningState(boolean running) {
        this.isRunning = running;
        startBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        dirField.setEnabled(!running);
        maxFsSpinner.setEnabled(!running);
        maxFsUnitCombo.setEnabled(!running);
        nbSpinner.setEnabled(!running);
        paradigmCombo.setEnabled(!running);
    }

    private String getBandRangeLabel(int index, long maxFS, int nb, SizeUnit unit) {
        if (index == nb) {
            return String.format("> %s", unit.format(maxFS));
        }
        double bandWidth = (double) maxFS / nb;
        long min = Math.round(index * bandWidth);
        long max = Math.round((index + 1) * bandWidth) - 1;
        if (index == nb - 1) {
            max = maxFS;
        }
        return String.format("[%s - %s]", unit.format(min), unit.format(max));
    }

    /**
     * Entry point to launch the interactive Swing GUI application.
     *
     * @param args Command-line arguments (ignored).
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            FSStatGUI frame = new FSStatGUI();
            frame.setVisible(true);
        });
    }
}
