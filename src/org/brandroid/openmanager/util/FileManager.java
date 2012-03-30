/*
    Open Explorer, an open source file explorer & text editor
    Copyright (C) 2011 Brandon Bowles <brandroid64@gmail.com>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.brandroid.openmanager.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Stack;
import java.io.File;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import jcifs.smb.SmbAuthException;
import jcifs.smb.SmbException;
import jcifs.smb.SmbFile;

import org.apache.commons.net.ftp.FTPFile;
import org.brandroid.openmanager.data.OpenFTP;
import org.brandroid.openmanager.data.OpenPath;
import org.brandroid.openmanager.data.OpenFile;
import org.brandroid.openmanager.data.OpenSCP;
import org.brandroid.openmanager.data.OpenSFTP;
import org.brandroid.openmanager.data.OpenSMB;
import org.brandroid.openmanager.data.OpenServer;
import org.brandroid.openmanager.data.OpenServers;
import org.brandroid.openmanager.data.OpenStack;
import org.brandroid.openmanager.ftp.FTPManager;
import org.brandroid.openmanager.ftp.FTPFileComparer;
import org.brandroid.openmanager.util.FileManager.SortType;
import org.brandroid.utils.Logger;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.UserInfo;

import android.net.Uri;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.Log;

/**
 * This class is completely modular, which is to say that it has
 * no reference to the any GUI activity. This class could be taken
 * and placed into in other java (not just Android) project and work.
 * <br>
 * <br>
 * This class handles all file and folder operations on the system.
 * This class dictates how files and folders are copied/pasted, (un)zipped
 * renamed and searched. The EventHandler class will generally call these
 * methods and have them performed in a background thread. Threading is not
 * done in this class.  
 * 
 * @author Joe Berria
 *
 */
public class FileManager {
	public static final int BUFFER = 512 * 1024;
	
	private boolean mShowHiddenFiles = false;
	private SortType mSorting = SortType.ALPHA;
	private long mDirSize = 0;
	private ArrayList<OpenPath> mDirContent;
	private OpenStack mPathStack;
	private static Hashtable<String, OpenPath> mOpenCache = new Hashtable<String, OpenPath>();
	public static UserInfo DefaultUserInfo;
	
	public static enum SortType {
		NONE,
		ALPHA,
		TYPE,
		SIZE,
		SIZE_DESC,
		DATE,
		DATE_DESC,
		ALPHA_DESC
	}
	
	/**
	 * Constructs an object of the class
	 * <br>
	 * this class uses a stack to handle the navigation of directories.
	 */
	public FileManager() {
		mDirContent = new ArrayList<OpenPath>();
		mPathStack = new OpenStack();
	}
	
	public OpenStack getStack() { return mPathStack; }
	
	public OpenPath peekStack() {
		return mPathStack.peek();
	}
	public void clearStack() {
		mPathStack.clear();
	}
	public OpenPath popStack() {
		return mPathStack.pop();
	}
	public OpenPath pushStack(OpenPath file) {
		return mPathStack.push(file);
	}
	public OpenPath setHomeDir(OpenPath home)
	{
		mPathStack.clear();
		return pushStack(home);
	}

	
	
	/**
	 * 
	 * @param old		the file to be copied
	 * @param newDir	the directory to move the file to
	 * @return
	 */
	public int copyToDirectory(String old, String newDir) {
		final File old_file = new File(old);
		final File temp_dir = new File(newDir);
		final byte[] data = new byte[BUFFER];
		int read = 0;
		
		if(old_file.isFile() && temp_dir.isDirectory() && temp_dir.canWrite()){
			String file_name = old.substring(old.lastIndexOf("/"), old.length());
			File cp_file = new File(newDir + file_name);
			if(cp_file.equals(old_file)) return 0;

			try {
				BufferedInputStream i_stream = new BufferedInputStream(
											   new FileInputStream(old_file));
				BufferedOutputStream o_stream = new BufferedOutputStream(
						new FileOutputStream(cp_file));
				
				while((read = i_stream.read(data, 0, BUFFER)) != -1)
					o_stream.write(data, 0, read);
				
				o_stream.flush();
				i_stream.close();
				o_stream.close();
				
			} catch (FileNotFoundException e) {
				Log.e("FileNotFoundException", e.getMessage());
				return -1;
				
			} catch (IOException e) {
				Log.e("IOException", e.getMessage());
				return -1;
			}
			
		}else if(old_file.isDirectory() && temp_dir.isDirectory() && temp_dir.canWrite()) {
			String files[] = old_file.list();
			String dir = newDir + old.substring(old.lastIndexOf("/"), old.length());
			int len = files.length;
			
			if(!new File(dir).mkdir())
				return -1;
			
			for(int i = 0; i < len; i++)
				copyToDirectory(old + "/" + files[i], dir);
			
		} else if(!temp_dir.canWrite())
			return -1;
		
		return 0;
	}
	
	/**
	 * 
	 * @param zipName
	 * @param toDir
	 * @param fromDir
	 */
	public void extractZipFilesFromDir(OpenPath zip, OpenPath directory) {
		if(!directory.mkdir() && directory.isDirectory()) return;
		extractZipFiles(zip, directory);
	}
	
	/**
	 * 
	 * @param zip_file
	 * @param directory
	 */
	public void extractZipFiles(OpenPath zip, OpenPath directory) {
		byte[] data = new byte[BUFFER];
		ZipEntry entry;
		ZipInputStream zipstream;
		
		directory.mkdir();
		
		try {
			zipstream = new ZipInputStream(zip.getInputStream());
			
			while((entry = zipstream.getNextEntry()) != null) {
				OpenPath newFile = directory.getChild(entry.getName());
				if(!newFile.mkdir())
					continue;
				
				int read = 0;
				FileOutputStream out = null;
				try {
					out = (FileOutputStream)newFile.getOutputStream();
					while((read = zipstream.read(data, 0, BUFFER)) != -1)
						out.write(data, 0, read);
				} catch(Exception e) { Logger.LogError("Error unzipping " + zip.getAbsolutePath(), e); }
				finally {
					zipstream.closeEntry();
					if(out != null)
						out.close();
				}
			}

		} catch (FileNotFoundException e) {
			Logger.LogError("Couldn't find file.", e);
			
		} catch (IOException e) {
			Logger.LogError("Couldn't extract zip.", e);
		}
	}
	
	/**
	 * 
	 * @param path
	 */
	public void createZipFile(OpenPath zip, OpenPath[] files) {
		ZipOutputStream zout = null;
		try {
			zout = new ZipOutputStream(
									  new BufferedOutputStream(
									  new FileOutputStream(((OpenFile)zip).getFile()), BUFFER));
			
			for(OpenPath file : files)
			{
				try {
					zipIt(file, zout);
				} catch(IOException e) {
					Logger.LogError("Error zipping file.", e);
				}
			}

			zout.close();
			
		} catch (FileNotFoundException e) {
			Log.e("File not found", e.getMessage());

		} catch (IOException e) {
			Log.e("IOException", e.getMessage());
		} finally {
			if(zout != null)
				try {
					zout.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
		}
	}
	private void zipIt(OpenPath file, ZipOutputStream zout) throws IOException
	{
		byte[] data = new byte[BUFFER];
		int read;
		
		if(file.isFile()){
			ZipEntry entry = new ZipEntry(file.getName());
			zout.putNextEntry(entry);
			BufferedInputStream instream = new BufferedInputStream(file.getInputStream());
			Log.e("File Manager", "zip_folder file name = " + entry.getName());
			while((read = instream.read(data, 0, BUFFER)) != -1)
				zout.write(data, 0, read);
			
			zout.closeEntry();
			instream.close();
		
		} else if (file.isDirectory()) {
			Log.e("File Manager", "zip_folder dir name = " + file.getPath());
			for(OpenPath kid : file.list())
				zipIt(kid, zout);
		}
	}
	
	/**
	 * 
	 * @param filePath
	 * @param newName
	 * @return
	 */
	public int renameTarget(String filePath, String newName) {
		File src = new File(filePath);
		String ext = "";
		File dest;
		
		if(src.isFile())
			/*get file extension*/
			ext = filePath.substring(filePath.lastIndexOf("."), filePath.length());
		
		if(newName.length() < 1)
			return -1;
	
		String temp = filePath.substring(0, filePath.lastIndexOf("/"));
		
		dest = new File(temp + "/" + newName + ext);
		if(src.renameTo(dest))
			return 0;
		else
			return -1;
	}
	
	/**
	 * 
	 * @param path
	 * @param name
	 * @return
	 */
	public int createDir(String path, String name) {
		int len = path.length();
		
		if(len < 1 || len < 1)
			return -1;
		
		if(path.charAt(len - 1) != '/')
			path += "/";
		
		if (new File(path+name).mkdir())
			return 0;
		
		return -1;
	}
	
	/**
	 * The full path name of the file to delete.
	 * 
	 * @param path name
	 * @return Number of Files deleted
	 */
	public int deleteTarget(OpenPath target) {
		
		int ret = 0;
		
		if(target.exists() && target.isFile() && target.canWrite())
			if(target.delete())
				ret++;
		
		else if(target.exists() && target.isDirectory() && target.canRead()) {
			OpenPath[] file_list = null;
			try {
				file_list = target.list();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			if(file_list != null && file_list.length == 0) {
				if(target.delete())
					ret++;
				
			} else if(file_list != null && file_list.length > 0) {
				
				for(int i = 0; i < file_list.length; i++)
				{
					OpenPath f = file_list[i];
					if(f.isDirectory())
						ret += deleteTarget(f);
					else if(f.isFile())
						if(f.delete())
							ret++;
				}
			}
			if(target.exists())
				if(target.delete())
					ret++;
		}	
		return ret;
	}
		
	/**
	 * converts integer from wifi manager to an IP address. 
	 * 
	 * @param des
	 * @return
	 */
	public static String integerToIPAddress(int ip) {
		String ascii_address = "";
		int[] num = new int[4];
		
		num[0] = (ip & 0xff000000) >> 24;
		num[1] = (ip & 0x00ff0000) >> 16;
		num[2] = (ip & 0x0000ff00) >> 8;
		num[3] = ip & 0x000000ff;
		 
		ascii_address = num[0] + "." + num[1] + "." + num[2] + "." + num[3];
		 
		return ascii_address;
	 }
	
	public static boolean hasOpenCache(String path) { return mOpenCache != null && mOpenCache.containsKey(path); }
	public static OpenPath getOpenCache(String path) throws IOException { return getOpenCache(path, false); }
	public static OpenPath removeOpenCache(String path) { return mOpenCache.remove(path); }
	
	public static OpenPath getOpenCache(String path, Boolean bGetNetworkedFiles)
			throws IOException, SmbAuthException, SmbException
	{
		//Logger.LogDebug("Checking cache for " + path);
		if(mOpenCache == null)
			mOpenCache = new Hashtable<String, OpenPath>();
		OpenPath ret = mOpenCache.get(path);
		if(ret == null)
		{
			if(path.startsWith("ftp:/"))
			{
				Logger.LogDebug("Checking cache for " + path);
				FTPManager man = new FTPManager(path);
				FTPFile file = new FTPFile();
				file.setName(path.substring(path.lastIndexOf("/")+1));
				Uri uri = Uri.parse(path);
				OpenServer server = OpenServers.DefaultServers.findByHost("ftp", uri.getHost());
				man.setUser(server.getUser());
				man.setPassword(server.getPassword());
				ret = new OpenFTP(null, file, man);
			} else if(path.startsWith("scp:/"))
			{
				Uri uri = Uri.parse(path);
				ret = new OpenSCP(uri.getHost(), uri.getUserInfo(), uri.getPath(), null);
			} else if(path.startsWith("sftp:/"))
			{
				Uri uri = Uri.parse(path);
				ret = new OpenSFTP(uri);
			} else if(path.startsWith("smb:/"))
			{
				try {
					Uri uri = Uri.parse(path);
					String user = uri.getUserInfo();
					if(user != null && user.indexOf(":") > -1)
						user = user.substring(0, user.indexOf(":"));
					else user = "";
					OpenServer server = OpenServers.DefaultServers.findByPath("smb", uri.getHost(), user, uri.getPath());
					if(server == null)
						server = OpenServers.DefaultServers.findByUser("smb", uri.getHost(), user);
					if(server == null)
						server = OpenServers.DefaultServers.findByHost("smb", uri.getHost());
					if(server != null && server.getPassword() != null && server.getPassword() != "")
						user += ":" + server.getPassword();
					if(!user.equals(""))
						user += "@";
					ret = new OpenSMB(uri.getScheme() + "://" + user + uri.getHost() + uri.getPath());
				} catch(Exception e) {
					Logger.LogError("Couldn't get samba from cache.", e);
				}
			}
			if(ret == null) return ret;
			if(bGetNetworkedFiles)
			{
				if(ret.listFiles() != null)
					setOpenCache(path, ret);
			} else {
				ret.listFromDb();
			}
		}
		//if(ret == null)
		//	ret = setOpenCache(path, new OpenFile(path));
		//else setOpenCache(path, ret);
		return ret;
	}
	
	public static OpenPath setOpenCache(String path, OpenPath file)
	{
		mOpenCache.put(path, file);
		return file;
	}
	
	public static void addCacheToDb()
	{
		for(OpenPath path : mOpenCache.values())
			path.addToDb();
	}
	
	public OpenPath[] getChildren(OpenPath directory) throws IOException
	{
		//mDirContent.clear();
		if(directory == null) return new OpenPath[0];
		if(!directory.isDirectory()) return new OpenPath[0];
		return directory.list();
	}

	public static SortType parseSortType(String setting) {
		for(SortType type : SortType.values())
			if(type.toString().equalsIgnoreCase(setting))
				return type;
		return SortType.ALPHA;	
	}
}

