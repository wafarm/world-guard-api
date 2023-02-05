#!/usr/bin/env bash

folder="./build/artifacts"
mkdir $folder -p

version=$(cat gradle.properties | grep mod_version | cut -d "=" -f 2 | xargs)
version="${version%%[[:cntrl:]]}"
targets=("forge" "fabric")
for target in "${targets[@]}"; do
  cp "$target/build/libs/worldguard-$version.jar" "$folder/worldguard-$version-$target.jar"
  cp "$target/build/libs/worldguard-$version.jar" "$folder/worldguard-$version-$target-client.jar"
done

files=$(ls $folder | grep client)

for file in $files; do
  zip -d -qq "$folder/$file" "com/github/zly2006/worldguard/mixin/*"
  zip -d -qq "$folder/$file" "com/github/zly2006/worldguard/access/*"
done
