#include <jni.h>
#include <string>
#include <sstream>
#include <fstream>
#include <cstdio>
extern "C" {
#include "mobi.h"
}
extern "C" JNIEXPORT jstring JNICALL Java_dev_homescreenreader_NativeReader_unpackMobi(JNIEnv* e,jobject,jstring path,jstring dest) {
 auto s=e->GetStringUTFChars(path,nullptr); auto d=e->GetStringUTFChars(dest,nullptr); std::string dir(d); auto m=mobi_init(); FILE* f=fopen(s,"rb");
 std::string error; std::ostringstream names;
 if(!f || mobi_load_file(m,f)!=MOBI_SUCCESS) error="Invalid MOBI/AZW3 file";
 if(f) fclose(f);
 if(error.empty() && mobi_is_encrypted(m)) error="DRM encrypted Kindle books are unsupported";
 MOBIRawml* raw=nullptr;
 if(error.empty()) {raw=mobi_init_rawml(m); if(!raw || mobi_parse_rawml(raw,m)!=MOBI_SUCCESS) error="Unable to decode Kindle book";}
 if(error.empty()) {
  char title[2048]={0}; mobi_get_fullname(m,title,sizeof(title)-1); names<<title<<'\n';auto author=mobi_meta_get_author(m);names<<(author?author:"")<<'\n';free(author);
  for(auto part=raw->markup;part;part=part->next){ char name[64]; snprintf(name,sizeof(name),"part%05zu.html",part->uid); std::ofstream out(dir+"/"+name,std::ios::binary); out.write((char*)part->data,part->size); names<<name<<'\n'; }
  for(auto part=raw->resources;part;part=part->next){auto meta=mobi_get_filemeta_by_type(part->type); char name[128]; snprintf(name,sizeof(name),"resource%05zu.%s",part->uid,meta.extension); std::ofstream out(dir+"/"+name,std::ios::binary); out.write((char*)part->data,part->size);}
 }
 if(raw) mobi_free_rawml(raw); mobi_free(m); e->ReleaseStringUTFChars(path,s);e->ReleaseStringUTFChars(dest,d);
 if(!error.empty()){e->ThrowNew(e->FindClass("java/lang/IllegalArgumentException"),error.c_str());return nullptr;}
 return e->NewStringUTF(names.str().c_str());
}
