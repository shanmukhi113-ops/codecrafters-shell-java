[![progress-banner](https://backend.codecrafters.io/progress/shell/ac9543ec-bcdf-45c3-ad86-cc471127c98f)](https://app.codecrafters.io/users/shanmukhi113-ops?r=2qF)

# CodeCrafters Shell (Java)

A POSIX-style shell built from scratch in Java as part of the CodeCrafters "Build Your Own Shell" challenge.

## Features

* Execute external commands
* Built-in commands:

  * `echo`
  * `pwd`
  * `cd`
  * `type`
  * `jobs`
  * `complete`
* Command parsing with quote handling
* Pipelines (`|`)
* Output redirection (`>`, `>>`)
* Error redirection (`2>`, `2>>`)
* Background job execution (`&`)
* Job control and job listing
* Job number recycling
* Shell command completion support

## Technologies Used

* Java
* ProcessBuilder API
* Linux / POSIX concepts
* Git & GitHub

## Challenge Progress

✅ Completed all mandatory stages of the CodeCrafters Shell Challenge.

## Architecture

The shell is implemented in Java using:

- Command parsing and tokenization
- Built-in command handlers
- ProcessBuilder for external command execution
- Pipeline management using process streams
- Background job tracking and lifecycle management
- Redirection handling for stdout and stderr

## Running the Project

```bash
mvn package
./your_program.sh
```

## Example Usage

```bash
$ echo Hello
Hello

$ pwd
/home/user

$ sleep 5 &
[1] 12345

$ jobs
[1]+ Running sleep 5 &
```

## What I Learned

* Shell architecture and REPL design
* Process creation and management
* Pipes and redirections
* Background job handling
* Command parsing and tokenization
* Unix shell fundamentals

```
```
