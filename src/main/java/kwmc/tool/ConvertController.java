package kwmc.tool;

import com.ibm.icu.text.CharsetDetector;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipException;

public class ConvertController {

    @FXML
    public MenuBar menuBar;

    @FXML
    public CheckMenuItem itemSlow;

    @FXML
    public CheckMenuItem itemMedium;

    @FXML
    public CheckMenuItem itemFast;

    @FXML
    private Button convertBtn;

    @FXML
    private Button chooseFileBtn;

    @FXML
    private TextField fileNameTextField;

    @FXML
    private ProgressBar progressBar;

    private File file;

    private File fileSave;

    private Map<String,String> fileMap;

    private int numberOfFile;

    List<Future<?>> futureFiles;

    final String[] searchElements = {"From:","To:","CC:","BCC:","Subject:"};

    private int speed = 10;

    ExecutorService executorFile;
    ExecutorService executorLine;

    @FXML
    protected void selectedSlow() {
        this.speed = 2;
        this.itemMedium.setSelected(false);
        this.itemFast.setSelected(false);
    }

    @FXML
    protected void selectedMedium() {
        this.speed = 10;
        this.itemSlow.setSelected(false);
        this.itemFast.setSelected(false);
    }

    @FXML
    protected void selectedFast() {
        this.speed = 50;
        this.itemMedium.setSelected(false);
        this.itemSlow.setSelected(false);
    }

    @FXML
    protected void chooseFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("*.zip", "*.zip"));
        file = fileChooser.showOpenDialog(chooseFileBtn.getScene().getWindow());
        if (file != null) {
            fileNameTextField.setText(file.getAbsolutePath());
        }
    }

    @FXML
    protected void convert() {
        if (file == null){
            showAlert("Please choose a file");
            return;
        }
        String[] fileSplit = file.getName().split("\\.");
        if (!"zip".equalsIgnoreCase(fileSplit[fileSplit.length-1])){
            showAlert("File must be zip file");
            return;
        }


        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save");
        fileChooser.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("*.zip", "*.zip"));
        File selectedFile = fileChooser.showSaveDialog(convertBtn.getScene().getWindow());
        if (selectedFile != null) {
            fileSave = selectedFile;
            convertBeckyToKwmc(file);
        }
    }

    private void showAlert(String text) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Error");
        alert.setHeaderText("Convert Error");
        alert.setContentText(text);
        alert.showAndWait();
    }

    private void showAlertSuccess(){
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Information");
        alert.setHeaderText("Convert success");
        alert.show();
    }

    private void convertBeckyToKwmc(File file) {
        executorFile = Executors.newFixedThreadPool(speed);
        executorLine = Executors.newFixedThreadPool(speed);
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
            bufferedInputStream.mark(fileInputStream.available());
            ZipArchiveInputStream zipArchiveInputStream = new ZipArchiveInputStream(bufferedInputStream, "MS932");
            Map<String, byte[]> zipArchiveEntryMap = getZipArchiveEntry(zipArchiveInputStream);
            this.numberOfFile = zipArchiveEntryMap.size();
            processConvert(zipArchiveEntryMap);


        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Map<String, byte[]> getZipArchiveEntry(ZipArchiveInputStream zipInputStream) throws IOException {
        Map<String, byte[]> zipArchiveEntryMap = new LinkedHashMap<>();

        try {
            ZipArchiveEntry entry;

            while ((entry = zipInputStream.getNextZipEntry()) != null) {
                zipArchiveEntryMap.put(entry.getName(), IOUtils.toByteArray(zipInputStream));
            }
        } catch (Exception e) {
            throw new ZipException("Unexpected record signature");
        }

        zipInputStream.close();

        return zipArchiveEntryMap;
    }

    private void processConvert(Map<String, byte[]> zipArchiveEntryMap) {
        fileMap = new HashMap<>();
        progressBar.setVisible(true);
        new LoadingBar().start();
        futureFiles = new LinkedList<>();
        for (Map.Entry<String, byte[]> entry : zipArchiveEntryMap.entrySet()) {
            Future<?> f = executorFile.submit(new ConvertMailTemplate(entry));
            futureFiles.add(f);
        }

        new CompleteChecker().start();
    }

    private void processStoreResult(Map<String, String> fileMap) {

        try {
            ZipArchiveOutputStream zaos = new ZipArchiveOutputStream(fileSave);
            for (Map.Entry<String, String> entry : fileMap.entrySet()) {
                ZipArchiveEntry zipArchiveEntry = new ZipArchiveEntry(entry.getKey());
                zipArchiveEntry.setTime(new Date().getTime());
                zaos.putArchiveEntry(zipArchiveEntry);
                zaos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            zaos.closeArchiveEntry();
            zaos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String getCharsetEncode(byte[] buffer) {
        if (buffer == null) {
            throw new RuntimeException("Data can not be null");
        }
        CharsetDetector detector = new CharsetDetector();
        try {
            detector.setText(buffer);
        } catch (UnsupportedCharsetException e) {
            throw new RuntimeException(e);
        }
        String charsetEncode = detector.detect().getName();
        if (charsetEncode == null) {
            charsetEncode = "UTF-8";
        }
        return charsetEncode;
    }

    static class ExtractMailSource extends Thread {

        private final List<String> lineContents;
        private final String searchElement;
        Map<String, String> result;

        public ExtractMailSource(List<String> lineContents, Map<String, String> result, String searchElement) {
            this.lineContents = lineContents;
            this.result = result;
            this.searchElement = searchElement;
        }

        @Override
        public void run() {
            Optional<String> lineOptional = lineContents.stream()
                    .filter(line -> line.toUpperCase().contains(searchElement.toUpperCase()))
                    .findFirst();

            int index = lineOptional.map(lineContents::indexOf).orElse(-1);
            if (index != -1){
                StringBuilder stringBuilder = new StringBuilder();
                if (!"Subject:".equals(searchElement)){
                    stringBuilder.append(lineContents.get(index));
                    while(!lineContents.get(index+1).contains(":")){
                        stringBuilder.append(lineContents.get(index+1));
                        index++;
                    }
                }else {
                    if (lineContents.get(index).toLowerCase().contains("iso-2022-jp")){
                        try {
                            stringBuilder.append("Subject: ").append(base64DecodeCustom(lineContents.get(index).split("Subject:")[1].trim())).append("\n");
                        } catch (UnsupportedEncodingException e) {
                            e.printStackTrace();
                        }
                    } else {
                        stringBuilder.append(lineContents.get(index));
                    }
                    while(!lineContents.get(index+1).contains(":")){
                        if (lineContents.get(index+1).toLowerCase().contains("iso-2022-jp")){
                            try {
                                stringBuilder.append(base64DecodeCustom(lineContents.get(index+1).trim())).append("\n");
                            } catch (UnsupportedEncodingException e) {
                                e.printStackTrace();
                            }
                        }else {
                            stringBuilder.append(lineContents.get(index+1));
                        }
                        index++;
                    }
                }
                result.put(searchElement, stringBuilder.toString());
            }else{
                result.put(searchElement,searchElement+"\n");
            }
        }

        public String base64DecodeCustom(String encodedString) throws UnsupportedEncodingException {
            return new String(Base64.getDecoder().decode(encodedString.split("\\?")[3].getBytes()), "iso-2022-jp");
        }
    }


    class ConvertMailTemplate extends Thread {
        private final Map.Entry<String, byte[]> entry;


        public ConvertMailTemplate(Map.Entry<String, byte[]> entry) {
            this.entry = entry;
        }


        @Override
        public void run() {
            String charset = getCharsetEncode(entry.getValue());
            try {
                List<String> lineContents = Arrays.asList(new String(entry.getValue(), charset).split("\n"));
                Map<String,String> result = new HashMap<>();
                List<Future<?>> futureLineStrings = new ArrayList<>();
                try {
                    for (String searchElement: searchElements){
                        Future<?> f = executorLine.submit(new ExtractMailSource(lineContents, result, searchElement));
                        futureLineStrings.add(f);
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }finally {
                    int firstIndex = lineContents.indexOf(lineContents.stream()
                            .filter(line -> line.contains("Content-Transfer-Encoding:"))
                            .findFirst().orElse("Content-Type:"));

                    int lastIndex = lineContents.size();

                    StringBuilder contentBuilder = new StringBuilder();
                    contentBuilder.append("Content:");
                    for (int i = firstIndex+2; i < lastIndex ; i++) {
                        contentBuilder.append(lineContents.get(i));
                    }
                    result.put("Content:", contentBuilder.toString());

                    for(Future<?> future: futureLineStrings){
                        future.get();
                    }

                    StringBuilder stringBuilder = new StringBuilder();
                    for (String searchEle: searchElements){
                        stringBuilder.append(result.get(searchEle));
                    }
                    stringBuilder.append(result.get("Content:"));
                    fileMap.put(entry.getKey(), stringBuilder.toString());
                }
            } catch (IOException | InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
    }

    class LoadingBar extends Thread {
        @Override
        public void run() {
            while(fileMap.size() < numberOfFile){
                progressBar.setProgress(fileMap.size()*1.0/numberOfFile);
            }
        }
    }

    class CompleteChecker extends Thread {
        @Override
        public void run() {
            while(true){
                int count = 0;
                for(Future<?> future: futureFiles){
                    if (future.isDone()) {
                        count ++;
                    }
                }
                if (count == futureFiles.size()) {
                    System.out.println("CHECKER DONE");
                    progressBar.setProgress(1.0);
                    processStoreResult(fileMap);
                    Platform.runLater(ConvertController.this::showAlertSuccess);
                    break;
                }
            }
        }
    }
}