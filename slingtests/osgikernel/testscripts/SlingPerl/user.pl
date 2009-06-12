#!/usr/bin/perl

#{{{Documentation
=head1 SYNOPSIS

user perl script. Provides a means of managing users in sling from the command
line. The script also acts as a reference implementation for the User perl
library.

=head1 OPTIONS

Usage: perl user.pl [-OPTIONS [-MORE_OPTIONS]] [--] [PROGRAM_ARG1 ...]
The following options are accepted:

 -a (actOnUser)      - add specified user name.
 -c (actOnUser)      - change password of specified user name.
 -d (actOnUser)      - delete specified user name.
 -e (actOnUser)      - check whether specified user exists.
 -f (actOnFirstName) - first name of user being actioned.
 -l (actOnLastName)  - last name of user being actioned.
 --me                - me returns json representing authenticated user.
 -n (newPassword)    - Used with -c, new password to set.
 -p (password)       - Password of user performing actions.
 --sites             - list sites user is a member of.
 -t (threads)        - Used with -F, defines number of parallel
                       processes to have running through file.
 -v (actOnUser)      - view details for specified user in json format.
 -u (username)       - Name of user to perform any actions as.
 -E (actOnEmail)     - Email of user being actioned.
 -F (file)           - file containing list of users to be added.
 -L (log)            - Log script output to specified log file.
 -P (actOnPass)      - Password of user being actioned.
 -U (URL)            - URL for system being tested against.
 --auth (type)       - Specify auth type. If ommitted, default is used.
 --help or -?        - view the script synopsis and options.
 --man               - view the full script documentation.

Options may be merged together. -- stops processing of options.
Space is not required between options and their arguments.
For full details run: perl user.pl --man

=head1 Example Usage

=over

=item Authenticate and add user "testuser" with password "test" and email address "test@test.com":

 perl user.pl -U http://localhost:8080 -a testuser -P test -E test@test.com -u admin -p admin

=back

=cut
#}}}

#{{{imports
use strict;
use lib qw ( .. );
use Getopt::Long qw(:config bundling);
use Pod::Usage;
use Sling::User;
use Sling::UserAgent;
#}}}

#{{{options parsing
my $actOnEmail;
my $actOnFirst;
my $actOnLast;
my $actOnPass;
my $addUser;
my $auth;
my $changePassUser;
my $deleteUser;
my $existsUser;
my $file;
my $help;
my $log;
my $man;
my $meUser;
my $newPass;
my $numberForks = 1;
my $password;
my $sitesUser;
my $url = "http://localhost";
my $username;
my $viewUser;

GetOptions ( "a=s" => \$addUser,     "c=s" => \$changePassUser,
             "d=s" => \$deleteUser,  "e=s" => \$existsUser,
             "f=s" => \$actOnFirst,  "l=s" => \$actOnLast,
             "n=s" => \$newPass,     "p=s" => \$password,
	     "t=s" => \$numberForks, "u=s" => \$username,
	     "v=s" => \$viewUser,    "E=s" => \$actOnEmail,
	     "F=s" => \$file,        "L=s" => \$log,
	     "P=s" => \$actOnPass,   "U=s" => \$url,
	     "me" => \$meUser,       "sites" => \$sitesUser,
	     "auth=s" => \$auth,
             "help|?" => \$help, "man" => \$man) or pod2usage(2);

pod2usage(-exitstatus => 0, -verbose => 1) if $help;
pod2usage(-exitstatus => 0, -verbose => 2) if $man;

$numberForks = ( $numberForks || 1 );
$numberForks = ( $numberForks =~ /^[0-9]+$/ ? $numberForks : 1 );
$numberForks = ( $numberForks < 32 ? $numberForks : 1 );

$url =~ s/(.*)\/$/$1/;
$url = ( $url !~ /^http/ ? "http://$url" : "$url" );

#}}}

#{{{main execution path
if ( defined $file ) {
    my $message = "Adding users and password from file:\n";
    if ( defined $log ) {
        Sling::Print::print_file_lock( "$message", $log );
    }
    else {
        Sling::Print::print_lock( "$message" );
    }
    my @childs = ();
    for ( my $i = 0 ; $i < $numberForks ; $i++ ) {
	my $pid = fork();
	if ( $pid ) { push( @childs, $pid ); } # parent
	elsif ( $pid == 0 ) { # child
	    # Create a separate user agent per fork:
            my $lwpUserAgent = Sling::UserAgent::get_user_agent( $log, $url, $username, $password, $auth );
            my $user = new Sling::User( $url, $lwpUserAgent );
            $user->add_from_file( $file, $i, $numberForks, $log );
	    exit( 0 );
	}
	else {
            die "Could not fork $i!";
	}
    }
    foreach ( @childs ) { waitpid( $_, 0 ); }
}
else {
    my $lwpUserAgent = Sling::UserAgent::get_user_agent( $log, $url, $username, $password, $auth );
    my $user = new Sling::User( $url, $lwpUserAgent );

    if ( defined $existsUser ) {
        $user->exists( $existsUser, $log );
        print $user->{ 'Message' } . "\n";
    }
    elsif ( defined $meUser ) {
        $user->me( $log );
        print $user->{ 'Message' } . "\n";
    }
    elsif ( defined $sitesUser ) {
        $user->sites( $log );
        print $user->{ 'Message' } . "\n";
    }
    elsif ( defined $addUser ) {
        $user->add( $addUser, $actOnPass, $actOnEmail, $actOnFirst, $actOnLast, $log );
        print $user->{ 'Message' } . "\n";
    }
    elsif ( defined $changePassUser ) {
        $user->change_password( $changePassUser, $actOnPass, $newPass, $newPass, $log );
        print $user->{ 'Message' } . "\n";
    }
    elsif ( defined $deleteUser ) {
        $user->delete( $deleteUser, $log );
        print $user->{ 'Message' } . "\n";
    }
    elsif ( defined $viewUser ) {
        $user->view( $viewUser, $log );
        print $user->{ 'Message' } . "\n";
    }
}
#}}}

1;
