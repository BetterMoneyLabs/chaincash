
%Dec 2017

%Thanks to Easychair and Oleg Proposrov for their help with the class file.
%
% Require LaTeX 2.09 or later
%

\NeedsTeXFormat{LaTeX2e}[1995/12/01]
\ProvidesClass{ifcolog}[]
\def\@tempa#1#2\@nil{\edef\@classname{#1}}
\expandafter\@tempa\@currnamestack{}{}{}\@nil
\ifx\@classname\@empty \edef\@classname{\@currname}\fi



%
% Running heads definitions
%

\def\titlerunning#1{\gdef\@titleRunning{#1}}
\def\authorrunning#1{\gdef\@authorRunning{#1}}
\titlerunning{$\backslash$titlerunning}
\authorrunning{$\backslash$authorrunning}


% a4paper
\def\@PaperFormat{a4paper}

% portrait
\def\paperorientation#1{\gdef\@PaperOrientation{#1}}
\def\@PaperOrientation{}

\DeclareOption{portrait}{\paperorientation{}}
\DeclareOption{landscape}{\paperorientation{landscape}}

% preparing to redefine \ss for the smallcaps title environment
\let\oldss=\ss


%
% line spacing
%

\def\lineSpacing#1{\gdef\@LineSpacing{#1}}
\def\@LineSpacing{1.0}

\DeclareOption{singlespacing}{\lineSpacing{1.0}}
\DeclareOption{lineandhalfspacing}{\lineSpacing{1.5}}
\DeclareOption{doublespacing}{\lineSpacing{2.0}}

\DeclareOption{1.0}{\lineSpacing{1.0}}
\DeclareOption{1.5}{\lineSpacing{1.5}}
\DeclareOption{2.0}{\lineSpacing{2.0}}

\DeclareOption{1.0pt}{\lineSpacing{1.0}}
\DeclareOption{1.5pt}{\lineSpacing{1.5}}
\DeclareOption{2.0pt}{\lineSpacing{2.0}}


%
% Font point size; default is 11pt
%
% The \headheight will have to be changed later accordingly
% such that fancyhdr does not complain it is too small.
%

\def\baseFontSize#1{\gdef\@BaseFontSize{#1}}
\def\headHeightSize#1{\gdef\@HeadHeightSize{#1}}

\def\@BaseFontSize{11pt}
\def\@HeadHeightSize{13.6pt}

\DeclareOption{8pt}{\PackageWarning{ifcolog}{Option '\CurrentOption' is not supported.}}
\DeclareOption{9pt}{\PackageWarning{ifcolog}{Option '\CurrentOption' is not supported.}}
%\DeclareOption{10pt}{\baseFontSize{10pt}\headHeightSize{12.0pt}}
\DeclareOption{10pt}{\PackageWarning{ifcolog}{Option '\CurrentOption' is not supported.}}
\DeclareOption{11pt}{\baseFontSize{11pt}\headHeightSize{13.6pt}}
\DeclareOption{12pt}{\baseFontSize{12pt}\headHeightSize{14.5pt}}



%\ExecuteOptions{centertags,portrait,10pt,twoside,onecolumn,final}
\ExecuteOptions{}
\ProcessOptions\relax


%
% Required packages and classes.
%

% article
\LoadClass[%\@PaperFormat,
\@PaperOrientation,
%\@ColumnCount,
\@BaseFontSize,twoside,draft]{article}
%\LoadClass[\@PaperFormat,\@PaperOrientation,\@ColumnCount,\@BaseFontSize]{article}





% All \urls will be hyperlinked and will deal properly with special characters
\RequirePackage{url}

% TOC/thumnamil LHS preview in the PDFs as well as active URLs and other cross-refs
\RequirePackage[\@PaperFormat]{hyperref}

% Traditional graphics processing
\RequirePackage{graphicx}


%% Fonts, generally more compact but preserving point size
%% Latin modern

%\RequirePackage{mathptmx}
%\RequirePackage{mathpazo}

%\RequirePackage{cmbright}
\RequirePackage{lmodern}

\RequirePackage[T1]{fontenc}


% For algorithm and source code listings
\RequirePackage{listings}

% Improve layout of lists
\RequirePackage{mdwlist}

% Improve fonts 
\RequirePackage{microtype}
\usepackage{tgtermes}
\usepackage{fix-cm}

%% Different Math and non-Math symbols and definitions

\RequirePackage{latexsym}
\RequirePackage{amsthm}
\RequirePackage{empheq}

%% Line spacing to be applied AFTER the above space saving packages

\renewcommand{\baselinestretch}{\@LineSpacing}

%% Margins

\newlength{\@MarginSize}
\setlength{\@MarginSize}{2.2 cm}

% Head height is dependent on the font point size
\setlength{\headheight}{\@HeadHeightSize}

%\ifletterpaper
%	\immediate\write10{easychair: Selecting letter paper margin sizes.}
%	\RequirePackage[%
%		%showframe,
%		papersize={7in,10in}
%		lmargin=1.1in,
%		rmargin=1.1in,
%		tmargin=\@MarginSize,
%		bmargin=\@MarginSize,
%		includefoot]{geometry}
%\fi
%
%\ifafourpaper
%	\immediate\write10{easychair: Selecting A4 paper margin sizes.}
	\RequirePackage[%
        papersize={7in,10in},
%		lmargin=\@MarginSize,
%		rmargin=\@MarginSize,
%		tmargin=\@MarginSize,
%		bmargin=\@MarginSize,
		lmargin=18.9mm,
		rmargin=18.9mm,
		tmargin=22mm,
		bmargin=22mm,
		includefoot,
		includehead
		]{geometry}
%\fi

%\setlength{\textwidth}{6in}
%\setlength{\textheight}{8.5in}

%
% Volume
%

%\RequirePackage{lastpage}


% To ensure the footnotes are always at the bottom
\RequirePackage[bottom]{footmisc}


%
% Allow for more space to place floats.
%

\renewcommand{\topfraction}{0.95}
\renewcommand{\bottomfraction}{0.95}
\renewcommand{\textfraction}{0.05}
\renewcommand{\floatpagefraction}{0.8}


%
% Running heads and ``foots''
%

\RequirePackage{fancyhdr}
\pagestyle{fancy}


\fancyhead{}
		\fancyhead[RO]{
		\def\ss{\textup{\oldss}} %%changing \ss for smallcaps
		\textsc{\@titleRunning}
		\def\ss{\oldss} %% changing \ss back
		}
		\fancyhead[LE]{\textsc{\@authorRunning}}



% commands for setting up constant values, and their defaults
\newcommand{\titlethanks}[1]{\def\thankss{#1}}
\newcommand{\jreceived}[1]{\def\thisreceived{#1}}
\newcommand{\jvolume}[1]{\def\thisvolume{#1}}
\newcommand{\jnumber}[1]{\def\thisnumber{#1}}
\newcommand{\jyear}[1]{\def\thisyear{#1}}
%\newcommand{\exh}[1]{\def\thisexh{#1}}
\def\thankss{Thank the referees (or not), and anyone else for reasons relating to the paper as a whole, by adding $\backslash$titlethanks$\{$text$\}$ \emph{to the preamble} (or $\backslash$titlethanks$\{\}$ to delete this reminder).

For acknowledgements specific to the author (e.g. thanking a particular author's funding body), use $\backslash$thanks$\{$text$\}$ after the author name in the $\backslash$addauthor command (or do not add the command at all for no such addition).}
\def\thisvolume{$\backslash$jvolume}
\def\thisnumber{$\backslash$jnumber}
\def\thisyear{$\backslash$jyear}
\def\thisreceived{$\backslash$jreceived}
\def\thisjournal{$\backslash$journalname}



% redefining plain style for \maketitle
\def\firstfoottext{\parbox[b][0pt][b]{14cm}{
\raggedright \footnotesize
\raggedleft  Vol.~\thisvolume~No.~\thisnumber~\thisyear \\ \thisjournal}
}



\fancypagestyle{plain}{%
\fancyhf{}
\fancyfoot[C]{\firstfoottext}
}

\newlength{\firstpageextra}
\setlength{\firstpageextra}{\baselineskip}





\RequirePackage{xifthen}

\AtEndDocument{
\fancyfoot[C]{\fthepage}
\fancyfoot[R]{\footnotesize Received \thisreceived}
}

%\def\nopagenumber{\def\fthepage{}}
\def\fthepage{\thepage}

\fancyfoot{}
	\fancyfoot[CO,CE]{\fthepage
	}
	\renewcommand{\headrulewidth}{.4pt}
	\renewcommand{\footrulewidth}{0pt}

% Suppress the default date
\date{}

\newcommand{\HRule}{\rule{\linewidth}{0.4pt}}


%figures in boxes
\RequirePackage{float}
\floatstyle{boxed} \restylefloat{figure}
\floatstyle{plain} \restylefloat{table}

\RequirePackage{caption}
%addauthor environment



\newcommand\authifcologcls{}
\newcounter{aucifcologcls}
\addtocounter{aucifcologcls}{1}

\newcommand\addauthor[3][]{\addtocounter{aucifcologcls}{1}%
\expandafter\newcommand\csname\alph{aucifcologcls}uthifcologcls\endcsname{\noindent\addtocounter{aucifcologcls}{-1}\csname\alph{aucifcologcls}uthifcologcls\endcsname\addtocounter{aucifcologcls}{2} \noindent \begin{center} \textsc{#2} \\ \textit{#3} \ifthenelse{\isempty{#1}}{}{\\ \texttt{#1}}
 \end{center}}
}

\newcommand\printauthor{\csname\alph{aucifcologcls}uthifcologcls\endcsname}

\newcommand\tss{\textup{\ss}}


% Title
\let\oldmaketitle=\maketitle
\def\@maketitle{%
	\newpage
	\null
	\vspace{-1.6cm}
	\begin{center}%
%\HRule \par \vskip .3\baselineskip
		\let \footnote \thanks
		{\def\ss{\textup{\oldss}} %%changing \ss for smallcaps
		\LARGE{\textsc{\@title}}
		\def\ss{\oldss}  %% changing\ss back
\ifthenelse{\equal{\thankss}{}}{}{\footnotetext{\hspace{-17pt}\thankss}}
\par}
		\vskip 1.5\baselineskip
		%\begin{tabular}[t]{c}%
		%\@author
		\printauthor
		%\end{tabular}
		\par
		%\vskip 0.5\baselineskip
		%\@date
\par\vskip .5\baselineskip
\HRule
	\end{center}%
	
	\par
	%\vskip \baselineskip
\enlargethispage{-\firstpageextra}
}

%keywords in abstract
\def\keywordss{}
\newcommand\keywords[1]{\def\keywordss{#1}}

\let\oldabstract=\abstract
\let\endoldabstract=\endabstract
\renewenvironment{abstract}
{\begin{oldabstract}}
{\ifthenelse{\equal{\keywordss}{}}{}{\\\ \\ {\footnotesize {\bf Keywords: } \keywordss}}
\end{oldabstract}}

% Tighten up bibliography
\let\oldthebibliography=\thebibliography
\let\endoldthebibliography=\endthebibliography
\renewenvironment{thebibliography}[1]
{
	\small
	\begin{oldthebibliography}{#1}
		\setlength{\parskip}{2pt}
		\setlength{\itemsep}{0pt}
}
{
  \end{oldthebibliography}
}

\endinput
