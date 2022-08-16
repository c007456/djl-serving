import http from '../common/http'

export const logs = () =>{
  return http.requestGet('/logs');
}
export const inferenceAddress = () =>{
  return http.requestGet('/inferenceAddress');
}
export const logInfo = (name) =>{
  return http.requestGet('/logs/'+name);
}
export const download = (name) =>{
  return http.requestGetFile('/logs/download/'+name);
}
