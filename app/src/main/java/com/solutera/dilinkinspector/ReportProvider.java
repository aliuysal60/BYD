package com.solutera.dilinkinspector;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Grants read access to one explicitly shared export, never to arbitrary app files. */
public final class ReportProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File resolve(Uri uri)throws FileNotFoundException {
        try {
            if(!"com.solutera.dilinkinspector.reports".equals(uri.getAuthority())||uri.getPathSegments().size()!=1)throw new IOException("Invalid URI");
            File root=new File(getContext().getFilesDir(),"exports").getCanonicalFile();
            File file=new File(root,uri.getLastPathSegment()).getCanonicalFile();
            if(!root.equals(file.getParentFile())||!file.isFile()||!(file.getName().endsWith(".zip")||file.getName().endsWith(".txt")))throw new IOException("Invalid export");
            return file;
        }catch(IOException e){throw new FileNotFoundException(e.getMessage());}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){return uri.getLastPathSegment()!=null&&uri.getLastPathSegment().endsWith(".txt")?"text/plain":"application/zip";}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        try {
            File f=resolve(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;
            MatrixCursor c=new MatrixCursor(cols);Object[] row=new Object[cols.length];
            for(int i=0;i<cols.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(cols[i])?f.getName():OpenableColumns.SIZE.equals(cols[i])?f.length():null;
            c.addRow(row);return c;
        }catch(Exception e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues v){throw new UnsupportedOperationException("Read only");}
    @Override public int delete(Uri uri,String s,String[] args){throw new UnsupportedOperationException("Read only");}
    @Override public int update(Uri uri,ContentValues v,String s,String[] args){throw new UnsupportedOperationException("Read only");}
}
