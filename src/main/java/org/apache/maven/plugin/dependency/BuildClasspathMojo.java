package org.apache.maven.plugin.dependency;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.dependency.utils.DependencyUtil;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.maven.shared.artifact.filter.collection.ArtifactsFilter;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This goal will output a classpath string of dependencies from the local repository to a file or log.
 *
 * @author ankostis
 * @version $Id: BuildClasspathMojo.java 1451088 2013-02-28 04:22:41Z brianf $
 * @since 2.0-alpha-2
 */
@Mojo( name = "build-classpath", requiresDependencyResolution = ResolutionScope.TEST,
       defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true )
public class BuildClasspathMojo
    extends AbstractDependencyFilterMojo
    implements Comparator<Artifact>
{

    /**
     * Strip artifact version during copy (only works if prefix is set)
     */
    @Parameter( property = "mdep.stripVersion", defaultValue = "false" )
    private boolean stripVersion = false;

    /**
     * The prefix to prepend on each dependent artifact. If undefined, the paths refer to the actual files store in the
     * local repository (the stripVersion parameter does nothing then).
     */
    @Parameter( property = "mdep.prefix" )
    private String prefix;

    /**
     * The file to write the classpath string. If undefined, it just prints the classpath as [INFO].
     * This parameter is deprecated. Use outputFile instead.
     *
     * @since 2.0
     * @deprecated use outputFile instead
     */
    @Parameter( property = "mdep.cpFile" )
    private File cpFile;

    /**
     * A property to set to the content of the classpath string.
     */
    @Parameter( property = "mdep.outputProperty" )
    private String outputProperty;
    
    /**
     * The file to write the classpath string. If undefined, it just prints the classpath as [INFO].
     */
    @Parameter( property = "mdep.outputFile" )
    private File outputFile;

    /**
     * If 'true', it skips the up-to-date-check, and always regenerates the classpath file.
     */
    @Parameter( property = "mdep.regenerateFile", defaultValue = "false" )
    private boolean regenerateFile;

    /**
     * Override the char used between the paths. This field is initialized to contain the first character of the value
     * of the system property file.separator. On UNIX systems the value of this field is '/'; on Microsoft Windows
     * systems it is '\'. The default is File.separator
     *
     * @since 2.0
     */
    @Parameter( property = "mdep.fileSeparator", defaultValue = "" )
    private String fileSeparator;

    /**
     * Override the char used between path folders. The system-dependent path-separator character. This field is
     * initialized to contain the first character of the value of the system property path.separator. This character is
     * used to separate filenames in a sequence of files given as a path list. On UNIX systems, this character is ':';
     * on Microsoft Windows systems it is ';'.
     *
     * @since 2.0
     */
    @Parameter( property = "mdep.pathSeparator", defaultValue = "" )
    private String pathSeparator;

    /**
     * Replace the absolute path to the local repo with this property. This field is ignored it prefix is declared. The
     * value will be forced to "${M2_REPO}" if no value is provided AND the attach flag is true.
     *
     * @since 2.0
     */
    @Parameter( property = "mdep.localRepoProperty", defaultValue = "" )
    private String localRepoProperty;

    /**
     * Attach the classpath file to the main artifact so it can be installed and deployed.
     *
     * @since 2.0
     */
    @Parameter( defaultValue = "false" )
    boolean attach;

    /**
     * Write out the classpath in a format compatible with filtering (classpath=xxxxx)
     *
     * @since 2.0
     */
    @Parameter( property = "mdep.outputFilterFile", defaultValue = "false" )
    boolean outputFilterFile;

    /**
     * Either append the artifact's baseVersion or uniqueVersion to the filename.
     * Will only be used if {@link #isStripVersion()} is {@code false}.
     * @since 2.6
     */
    @Parameter( property = "mdep.useBaseVersion", defaultValue = "true" )
    protected boolean useBaseVersion = true;

    /**
     * Maven ProjectHelper
     */
    @Component
    private MavenProjectHelper projectHelper;

    boolean isFileSepSet = true;

    boolean isPathSepSet = true;

    /**
     * Main entry into mojo. Gets the list of dependencies and iterates through calling copyArtifact.
     *
     * @throws MojoExecutionException with a message if an error occurs.
     * @see #getDependencies
     * @see #copyArtifact(Artifact, boolean)
     */
    protected void doExecute()
        throws MojoExecutionException
    {

        if ( cpFile != null )
        {
            getLog().warn( "The parameter cpFile is deprecated. Use outputFile instead." );
            this.outputFile = cpFile;
        }

        // initialize the separators.
        isFileSepSet = StringUtils.isNotEmpty( fileSeparator );
        isPathSepSet = StringUtils.isNotEmpty( pathSeparator );

        //don't allow them to have absolute paths when they attach.
        if ( attach && StringUtils.isEmpty( localRepoProperty ) )
        {
            localRepoProperty = "${M2_REPO}";
        }

        Set<Artifact> artifacts = getResolvedDependencies( true );

        if ( artifacts == null || artifacts.isEmpty() )
        {
            getLog().info( "No dependencies found." );
        }

        List<Artifact> artList = new ArrayList<Artifact>( artifacts );

        StringBuilder sb = new StringBuilder();
        Iterator<Artifact> i = artList.iterator();

        if ( i.hasNext() )
        {
            appendArtifactPath( i.next(), sb );

            while ( i.hasNext() )
            {
                sb.append( isPathSepSet ? this.pathSeparator : File.pathSeparator );
                appendArtifactPath( (Artifact) i.next(), sb );
            }
        }

        String cpString = sb.toString();

        // if file separator is set, I need to replace the default one from all
        // the file paths that were pulled from the artifacts
        if ( isFileSepSet )
        {
            // Escape file separators to be used as literal strings
            final String pattern = Pattern.quote( File.separator );
            final String replacement = Matcher.quoteReplacement( fileSeparator );
            cpString = cpString.replaceAll( pattern, replacement );
        }

        //make the string valid for filtering
        if ( outputFilterFile )
        {
            cpString = "classpath=" + cpString;
        }

        if ( outputProperty != null )
        {
            project.getProperties().setProperty( outputProperty, cpString );
            if ( getLog().isDebugEnabled() )
            {
                getLog().debug( outputProperty + " = " + cpString );
            }
        }
        else if ( outputFile == null )
        {
            getLog().info( "Dependencies classpath:\n" + cpString );
        }
        else
        {
            if ( regenerateFile || !isUpdToDate( cpString ) )
            {
                storeClasspathFile( cpString, outputFile );
            }
            else
            {
                this.getLog().info( "Skipped writing classpath file '" + outputFile + "'.  No changes found." );
            }
        }
        if ( attach )
        {
            attachFile( cpString );
        }
    }

    protected void attachFile( String cpString )
        throws MojoExecutionException
    {
        File attachedFile = new File( project.getBuild().getDirectory(), "classpath" );
        storeClasspathFile( cpString, attachedFile );

        projectHelper.attachArtifact( project, attachedFile, "classpath" );
    }

    /**
     * Appends the artifact path into the specified StringBuilder.
     *
     * @param art
     * @param sb
     */
    protected void appendArtifactPath( Artifact art, StringBuilder sb )
    {
        if ( prefix == null )
        {
            String file = art.getFile().getPath();
            // substitute the property for the local repo path to make the classpath file portable.
            if ( StringUtils.isNotEmpty( localRepoProperty ) )
            {
                file = StringUtils.replace( file, getLocal().getBasedir(), localRepoProperty );
            }
            sb.append( file );
        }
        else
        {
            // TODO: add param for prepending groupId and version.
            sb.append( prefix );
            sb.append( File.separator );
            sb.append( DependencyUtil.getFormattedFileName( art, this.stripVersion, this.prependGroupId, this.useBaseVersion ) );
        }
    }

    /**
     * Checks that new classpath differs from that found inside the old classpathFile.
     *
     * @param cpString
     * @return true if the specified classpath equals to that found inside the file, false otherwise (including when
     *         file does not exists but new classpath does).
     */
    private boolean isUpdToDate( String cpString )
    {
        try
        {
            String oldCp = readClasspathFile();
            return ( cpString == oldCp || ( cpString != null && cpString.equals( oldCp ) ) );
        }
        catch ( Exception ex )
        {
            this.getLog().warn(
                "Error while reading old classpath file '" + outputFile + "' for up-to-date check: " + ex );

            return false;
        }
    }

    /**
     * It stores the specified string into that file.
     *
     * @param cpString the string to be written into the file.
     * @throws MojoExecutionException
     */
    private void storeClasspathFile( String cpString, File out )
        throws MojoExecutionException
    {
        //make sure the parent path exists.
        out.getParentFile().mkdirs();

        Writer w = null;
        try
        {
            w = new BufferedWriter( new FileWriter( out ) );
            w.write( cpString );
            getLog().info( "Wrote classpath file '" + out + "'." );
        }
        catch ( IOException ex )
        {
            throw new MojoExecutionException( "Error while writting to classpath file '" + out + "': " + ex.toString(),
                                              ex );
        }
        finally
        {
            IOUtil.close( w );
        }
    }

    /**
     * Reads into a string the file specified by the mojo param 'outputFile'. Assumes, the instance variable
     * 'outputFile' is not null.
     * 
     * @return the string contained in the classpathFile, if exists, or null otherwise.
     * @throws MojoExecutionException
     */
    protected String readClasspathFile()
        throws IOException
    {
        if ( outputFile == null )
        {
            throw new IllegalArgumentException(
                "The outputFile parameter cannot be null if the file is intended to be read." );
        }

        if ( !outputFile.isFile() )
        {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader r = null;

        try
        {
            r = new BufferedReader( new FileReader( outputFile ) );
            String l;
            while ( ( l = r.readLine() ) != null )
            {
                sb.append( l );
            }

            return sb.toString();
        }
        finally
        {
            IOUtil.close( r );
        }
    }

    /**
     * Compares artifacts lexicographically, using pattern [group_id][artifact_id][version].
     *
     * @param art1 first object
     * @param art2 second object
     * @return the value <code>0</code> if the argument string is equal to this string; a value less than
     *         <code>0</code> if this string is lexicographically less than the string argument; and a value greater
     *         than <code>0</code> if this string is lexicographically greater than the string argument.
     */
    public int compare( Artifact art1, Artifact art2 )
    {
        if ( art1 == art2 )
        {
            return 0;
        }
        else if ( art1 == null )
        {
            return -1;
        }
        else if ( art2 == null )
        {
            return +1;
        }

        String s1 = art1.getGroupId() + art1.getArtifactId() + art1.getVersion();
        String s2 = art2.getGroupId() + art2.getArtifactId() + art2.getVersion();

        return s1.compareTo( s2 );
    }

    protected ArtifactsFilter getMarkedArtifactFilter()
    {
        return null;
    }

    /**
     * @return the outputFile
     */
    public File getCpFile()
    {
        return this.outputFile;
    }

    /**
     * @param theCpFile the outputFile to set
     */
    public void setCpFile( File theCpFile )
    {
        this.outputFile = theCpFile;
    }

    /**
     * @return the outputProperty
     */
    public String getOutputProperty()
    {
        return this.outputProperty;
    }

    /**
     * @param theOutputProperty the outputProperty to set
     */
    public void setOutputProperty( String theOutputProperty )
    {
        this.outputProperty = theOutputProperty;
    }
	
    /**
     * @return the fileSeparator
     */
    public String getFileSeparator()
    {
        return this.fileSeparator;
    }

    /**
     * @param theFileSeparator the fileSeparator to set
     */
    public void setFileSeparator( String theFileSeparator )
    {
        this.fileSeparator = theFileSeparator;
    }

    /**
     * @return the pathSeparator
     */
    public String getPathSeparator()
    {
        return this.pathSeparator;
    }

    /**
     * @param thePathSeparator the pathSeparator to set
     */
    public void setPathSeparator( String thePathSeparator )
    {
        this.pathSeparator = thePathSeparator;
    }

    /**
     * @return the prefix
     */
    public String getPrefix()
    {
        return this.prefix;
    }

    /**
     * @param thePrefix the prefix to set
     */
    public void setPrefix( String thePrefix )
    {
        this.prefix = thePrefix;
    }

    /**
     * @return the regenerateFile
     */
    public boolean isRegenerateFile()
    {
        return this.regenerateFile;
    }

    /**
     * @param theRegenerateFile the regenerateFile to set
     */
    public void setRegenerateFile( boolean theRegenerateFile )
    {
        this.regenerateFile = theRegenerateFile;
    }

    /**
     * @return the stripVersion
     */
    public boolean isStripVersion()
    {
        return this.stripVersion;
    }

    /**
     * @param theStripVersion the stripVersion to set
     */
    public void setStripVersion( boolean theStripVersion )
    {
        this.stripVersion = theStripVersion;
    }

    public String getLocalRepoProperty()
    {
        return localRepoProperty;
    }

    public void setLocalRepoProperty( String localRepoProperty )
    {
        this.localRepoProperty = localRepoProperty;
    }

    public boolean isFileSepSet()
    {
        return isFileSepSet;
    }

    public void setFileSepSet( boolean isFileSepSet )
    {
        this.isFileSepSet = isFileSepSet;
    }

    public boolean isPathSepSet()
    {
        return isPathSepSet;
    }

    public void setPathSepSet( boolean isPathSepSet )
    {
        this.isPathSepSet = isPathSepSet;
    }
}
