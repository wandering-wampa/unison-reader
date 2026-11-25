package dev.homescreenreader;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract.Document;
import java.io.*;
import java.util.zip.*;

/** Installed only with the test APK; never serves private files. */
public final class TestDocuments extends ContentProvider {
    public boolean onCreate() { return true; }
    public String getType(Uri uri) { return "application/epub+zip"; }
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection == null ? new String[]{Document.COLUMN_DISPLAY_NAME,Document.COLUMN_SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i=0;i<columns.length;i++) {
            if (columns[i].equals(Document.COLUMN_DISPLAY_NAME)) row[i]="Readonly.epub";
            else if(columns[i].equals(Document.COLUMN_SIZE)) row[i]=content().length;
            else if(columns[i].equals(Document.COLUMN_DOCUMENT_ID)) row[i]="readonly";
            else if(columns[i].equals(Document.COLUMN_MIME_TYPE)) row[i]="application/epub+zip";
            else if(columns[i].equals(Document.COLUMN_FLAGS)) row[i]=0;
        }
        cursor.addRow(row); return cursor;
    }
    public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            new Thread(()->{try(OutputStream output=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])){output.write(content());}catch(IOException ignored){}}).start();
            return pipe[0];
        } catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
    public Uri insert(Uri uri,ContentValues values){return null;}
    public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
    public int delete(Uri uri,String selection,String[] args){throw new SecurityException("Read-only provider denied deletion");}
    public Bundle call(String method,String arg,Bundle extras){throw new SecurityException("Read-only provider denied deletion");}
    private byte[] content(){try{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            String[] paths={"META-INF/container.xml","book.opf","a.html"};
            String[] text={"<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>","<package><metadata><title>Read-only provider fixture</title></metadata><manifest><item id='a' href='a.html' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>","<html><body><p>This synthetic text checks explicit cleanup reporting.</p></body></html>"};
            for(int i=0;i<paths.length;i++){ZipEntry entry=new ZipEntry(paths[i]);entry.setTime(0);zip.putNextEntry(entry);zip.write(text[i].getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        }return bytes.toByteArray();
    }catch(IOException e){throw new IllegalStateException(e);}}
}
