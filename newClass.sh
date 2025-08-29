
if [ "$1" == "" ] || [ "$2" == "" ] || [ "$3" == "" ]; then echo "usage: newClass.sh <project> <package> <className>"; exit; fi
project=$1
package=$2
className=$3
path=$HOME/code/jlox/$project/src/main/java/$package/$className.java

echo "package $package;

class $className {

}" > $path
