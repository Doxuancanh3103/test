package kwmc.tool;

import com.ibm.icu.text.CharsetDetector;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
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
    private Button convertBtn;

    @FXML
    private Button chooseFileBtn;

    @FXML
    private TextField fileNameTextField;

    private File file;

    private File fileSave;

    final String[] searchElements = {"From:","To:","CC:","BCC:","Subject:"};

    ExecutorService executorService;

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
        }
        convertBeckyToKwmc(file);
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
        alert.setTitle("KWMC");
        alert.setHeaderText("Success");
        alert.show();
    }

    private void convertBeckyToKwmc(File file) {
        try {
            FileInputStream fileInputStream = new FileInputStream(file);
            BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
            bufferedInputStream.mark(fileInputStream.available());
            ZipArchiveInputStream zipArchiveInputStream = new ZipArchiveInputStream(bufferedInputStream, "MS932");
            Map<String, byte[]> zipArchiveEntryMap = getZipArchiveEntry(zipArchiveInputStream);
            executorService = Executors.newFixedThreadPool(zipArchiveEntryMap.size());
            Map<String, String> fileMap = processConvert(zipArchiveEntryMap);
            processStoreResult(fileMap);

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

    private Map<String, String> processConvert(Map<String, byte[]> zipArchiveEntryMap) {
        Map<String, String> fileMap = new HashMap<>();
        List<Future<?>> futureFiles = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : zipArchiveEntryMap.entrySet()) {
            Future<?> f = executorService.submit(new ConvertMailTemplate(entry,fileMap));
            futureFiles.add(f);
        }
        for(Future<?> future: futureFiles){
            try {
                future.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
        }
        return fileMap;
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
        }finally {
            showAlertSuccess();
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
                    .filter(line -> line.contains(searchElement))
                    .findFirst();

            int index = lineOptional.map(lineContents::indexOf).orElse(-1);
            if (index != -1){
                StringBuilder stringBuilder = new StringBuilder();
                stringBuilder.append(lineContents.get(index));
                while(!lineContents.get(index+1).contains(":")){
                    stringBuilder.append(lineContents.get(index+1));
                    index++;
                }
                result.put(searchElement, stringBuilder.toString());
            }else{
                result.put(searchElement,searchElement+"\n");
            }
        }
    }


    class ConvertMailTemplate extends Thread {
        private final Map.Entry<String, byte[]> entry;
        private final Map<String,String> fileMap;


        public ConvertMailTemplate(Map.Entry<String, byte[]> entry, Map<String,String> fileMap) {
            this.entry = entry;
            this.fileMap = fileMap;
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
                        Future<?> f = executorService.submit(new ExtractMailSource(lineContents, result, searchElement));
                        futureLineStrings.add(f);
                    }
                }catch (Exception e){
                    e.printStackTrace();
                }finally {
                    int firstIndex = lineContents.indexOf(lineContents.stream()
                            .filter(line -> line.contains("Content-Transfer-Encoding:"))
                            .findFirst().orElse("Content-Type:"));

                    int lastIndex = lineContents.lastIndexOf(lineContents.stream()
                            .filter(line -> line.contains("Tel"))
                            .findFirst().orElse("E-mail"));

                    if (lastIndex == -1) {
                        lastIndex = lineContents.size();
                    }

                    StringBuilder contentBuilder = new StringBuilder();
                    contentBuilder.append("Content:");
                    for (int i = firstIndex+2; i < lastIndex ; i++) {
                        contentBuilder.append(lineContents.get(i));
                    }
                    result.put("Content:", contentBuilder.toString());

                    for (Future<?> future: futureLineStrings){
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
}